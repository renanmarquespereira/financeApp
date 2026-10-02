import hashlib
import re
from datetime import datetime, timedelta, timezone
from decimal import Decimal

from app.core.workspace import bind_workspace
from sqlalchemy.orm import Session

from app.models.openfinance import OpenFinanceConnection, OpenFinanceSyncLog
from app.models.account import BankAccount
from app.models.credit_card import CreditCard
from app.models.transaction import Transaction
from app.models.ignored_transaction import IgnoredTransaction
from app.models.financial_reset_marker import FinancialResetMarker
from app.models.ignored_account import IgnoredAccount
from app.services.openfinance_provider import get_provider


def _card_last_four(remote_account: dict) -> str:
    candidates = [
        remote_account.get("masked"),
        (remote_account.get("raw") or {}).get("number"),
        (remote_account.get("raw") or {}).get("account_number"),
    ]

    for value in candidates:
        digits = re.sub(r"\D", "", str(value or ""))
        if len(digits) >= 4:
            return digits[-4:]

    external_id = str(remote_account.get("id") or "")
    digest = hashlib.sha256(
        external_id.encode("utf-8")
    ).hexdigest()
    return str(int(digest[:8], 16) % 10000).zfill(4)


def _automatic_card_for_account(
    db: Session,
    *,
    user_id: int,
    remote_account: dict,
    institution_name: str,
):
    if not remote_account.get("is_credit_card"):
        return None

    last_four = _card_last_four(remote_account)

    card = (
        db.query(CreditCard)
        .filter(
            CreditCard.user_id == user_id,
            CreditCard.bank_name == institution_name,
            CreditCard.last_four == last_four,
        )
        .first()
    )

    if card:
        if not card.active:
            card.active = True
        return card

    card = CreditCard(
        user_id=user_id,
        bank_name=institution_name,
        brand="Não informada",
        last_four=last_four,
        nickname=(
            remote_account.get("name")
            or "Cartão Open Finance"
        ),
        active=True,
    )
    db.add(card)
    db.flush()
    return card


def _installment_group(
    *,
    connection_id: int,
    remote_account: dict,
    item: dict,
):
    total = item.get("installment_total")
    number = item.get("installment_number")

    if not total or not number:
        return None

    purchase_id = item.get("purchase_id")

    if purchase_id:
        seed = (
            f"purchase:{connection_id}:"
            f"{remote_account.get('id')}:"
            f"{purchase_id}"
        )
    else:
        description = re.sub(
            r"(?<!\d)\d{1,2}\s*(?:/|de)\s*\d{1,2}(?!\d)",
            "",
            str(item.get("description") or ""),
            flags=re.IGNORECASE,
        )
        description = " ".join(
            description.lower().split()
        )

        amount = str(
            abs(
                Decimal(
                    str(item.get("amount") or 0)
                )
            )
        )

        date_value = item.get("date")
        first_charge_key = ""

        if isinstance(date_value, datetime):
            first_date = (
                date_value
                - timedelta(
                    days=30 * (int(number) - 1)
                )
            )
            first_charge_key = (
                f"{first_date.year:04d}-"
                f"{first_date.month:02d}"
            )

        seed = (
            f"heuristic:{connection_id}:"
            f"{remote_account.get('id')}:"
            f"{description}:{amount}:"
            f"{total}:{first_charge_key}"
        )

    digest = hashlib.sha256(
        seed.encode("utf-8")
    ).hexdigest()[:32]

    return f"of-installment:{digest}"



def _transaction_is_before_financial_reset(
    item: dict,
    reset_at: datetime | None,
) -> bool:
    if reset_at is None:
        return False

    value = item.get("date")
    if value is None:
        return False

    if isinstance(value, str):
        try:
            value = datetime.fromisoformat(
                value.replace("Z", "+00:00")
            )
        except ValueError:
            return False

    if not isinstance(value, datetime):
        return False

    tx_date = value
    if tx_date.tzinfo is None:
        tx_date = tx_date.replace(
            tzinfo=timezone.utc
        )

    cutoff = reset_at
    if cutoff.tzinfo is None:
        cutoff = cutoff.replace(
            tzinfo=timezone.utc
        )

    return tx_date <= cutoff


def sync_connection(db: Session, connection_id: int):
    # Serializa a sincronização por conexão. Isso evita dois workers
    # importarem o mesmo external_transaction_id ao mesmo tempo.
    conn = (
        db.query(OpenFinanceConnection)
        .filter(OpenFinanceConnection.id == connection_id)
        .with_for_update()
        .first()
    )
    if not conn:
        return {"status": "not_found", "imported": 0}

    bind_workspace(db, conn.user_id, conn.workspace_id)

    reset_marker = db.get(
        FinancialResetMarker,
        (conn.user_id, conn.workspace_id),
    )
    financial_reset_at = (
        reset_marker.reset_at
        if reset_marker is not None
        else None
    )

    log = OpenFinanceSyncLog(connection_id=conn.id, status="running")
    db.add(log)
    # Não faz commit aqui: o lock da conexão precisa permanecer ativo
    # durante toda a importação. O commit final libera o lock.
    db.flush()
    db.refresh(log)
    imported = 0

    try:
        provider = get_provider(conn.provider)
        if conn.status != "active":
            log.status = "skipped"
            log.message = f"Conexão com status {conn.status}; aguardando consentimento."
        else:
            for remote_account in provider.fetch_accounts(conn):
                external_id = str(remote_account["id"])

                ignored_account = (
                    db.query(IgnoredAccount)
                    .filter(
                        IgnoredAccount.user_id == conn.user_id,
                        IgnoredAccount.external_account_id == external_id,
                    )
                    .first()
                )
                if ignored_account:
                    continue

                account = (
                    db.query(BankAccount)
                    .filter(
                        BankAccount.user_id == conn.user_id,
                        BankAccount.external_account_id == external_id,
                    )
                    .first()
                )
                if not account:
                    account = BankAccount(
                        user_id=conn.user_id,
                        institution_name=(
                            remote_account.get("institution_name")
                            or conn.institution_name
                            or "Open Finance"
                        ),
                        institution_id=(
                            remote_account.get("institution_id") or conn.institution_id
                        ),
                        account_name=remote_account.get("name"),
                        masked_account=remote_account.get("masked"),
                        external_account_id=external_id,
                        current_balance=(
                            Decimal(
                                str(remote_account["balance"])
                            )
                            if remote_account.get("balance")
                            is not None
                            else None
                        ),
                        balance_updated_at=(
                            datetime.now(timezone.utc)
                            if remote_account.get("balance")
                            is not None
                            else None
                        ),
                    )
                    db.add(account)
                    db.flush()
                else:
                    # A identificação técnica continua atualizada,
                    # mas os textos exibidos podem ser personalizados
                    # pelo usuário e não devem ser sobrescritos.
                    account.institution_id = (
                        remote_account.get("institution_id")
                        or account.institution_id
                    )

                    if (
                        remote_account.get("balance")
                        is not None
                    ):
                        account.current_balance = Decimal(
                            str(remote_account["balance"])
                        )
                        account.balance_updated_at = (
                            datetime.now(timezone.utc)
                        )

                institution_name = (
                    remote_account.get("institution_name")
                    or conn.institution_name
                    or "Open Finance"
                )

                automatic_card = (
                    _automatic_card_for_account(
                        db,
                        user_id=conn.user_id,
                        remote_account=remote_account,
                        institution_name=institution_name,
                    )
                )

                for item in provider.fetch_transactions(conn, remote_account):
                    # O usuário pode escolher "recomeçar do zero".
                    # Nesse caso o banco continua conectado normalmente,
                    # mas o histórico anterior ao reset nunca volta ao app.
                    if _transaction_is_before_financial_reset(
                        item,
                        financial_reset_at,
                    ):
                        continue

                    txid = str(item["id"])
                    ignored = (
                        db.query(IgnoredTransaction)
                        .filter(
                            IgnoredTransaction.user_id == conn.user_id,
                            IgnoredTransaction.account_id == account.id,
                            IgnoredTransaction.external_transaction_id == txid,
                        )
                        .first()
                    )
                    if ignored:
                        continue

                    exists = (
                        db.query(Transaction)
                        .filter(
                            Transaction.account_id == account.id,
                            Transaction.external_transaction_id == txid,
                        )
                        .first()
                    )
                    if exists:
                        exists.source = "open_finance"
                        exists.synced_at = datetime.now(timezone.utc)

                        if automatic_card is not None:
                            exists.card_id = automatic_card.id

                        group = _installment_group(
                            connection_id=conn.id,
                            remote_account=remote_account,
                            item=item,
                        )

                        if group is not None:
                            exists.installment_group = group
                            exists.installment_number = (
                                item.get("installment_number")
                            )
                            exists.installment_total = (
                                item.get("installment_total")
                            )

                            purchase_date = item.get("purchase_date")
                            if isinstance(purchase_date, datetime):
                                exists.purchase_date = purchase_date

                        continue

                    installment_group = _installment_group(
                        connection_id=conn.id,
                        remote_account=remote_account,
                        item=item,
                    )

                    purchase_date = item.get("purchase_date")

                    db.add(
                        Transaction(
                            user_id=conn.user_id,
                            account_id=account.id,
                            card_id=(
                                automatic_card.id
                                if automatic_card is not None
                                else None
                            ),
                            installment_group=installment_group,
                            installment_number=(
                                item.get("installment_number")
                                if installment_group
                                else None
                            ),
                            installment_total=(
                                item.get("installment_total")
                                if installment_group
                                else None
                            ),
                            purchase_date=(
                                purchase_date
                                if isinstance(purchase_date, datetime)
                                else None
                            ),
                            external_transaction_id=txid,
                            source="open_finance",
                            date=item["date"],
                            description=item.get("description") or "Transação",
                            amount=Decimal(str(item["amount"])),
                            transaction_type=item.get("type", "unknown"),
                            status=item.get("status", "posted"),
                            synced_at=datetime.now(timezone.utc),
                        )
                    )
                    imported += 1

            log.status = "success"
            log.imported_transactions = imported
            log.message = "Sincronização concluída."

        now = datetime.now(timezone.utc)
        conn.last_sync_at = now
        # Default conservador. Webhooks continuam podendo disparar antes disso.
        conn.next_sync_at = now + timedelta(minutes=conn.sync_interval_minutes)
        conn.last_error = None
        log.finished_at = now
        db.commit()
        return {"status": log.status, "imported": imported, "source": "open_finance", "message": log.message}
    except Exception as exc:
        now = datetime.now(timezone.utc)
        conn.last_error = str(exc)[:4000]
        conn.next_sync_at = now + timedelta(minutes=15)
        log.status = "error"
        log.message = str(exc)[:4000]
        log.finished_at = now
        db.commit()
        raise

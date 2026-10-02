from __future__ import annotations

import re

from abc import ABC, abstractmethod
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx

from app.core.config import settings


def _normalize_balance_value(value):
    """Normaliza o saldo atual retornado pelos provedores."""
    if value is None:
        return None

    if isinstance(value, dict):
        for key in (
            "available",
            "current",
            "available_balance",
            "current_balance",
            "amount",
            "value",
        ):
            if value.get(key) is not None:
                return _normalize_balance_value(
                    value.get(key)
                )
        return None

    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _extract_installment_metadata(
    item: dict[str, Any],
    description: str,
) -> dict[str, Any]:
    raw_installment = (
        item.get("installment")
        or item.get("installments")
        or {}
    )

    number = (
        item.get("installment_number")
        or item.get("current_installment")
        or (
            raw_installment.get("number")
            if isinstance(raw_installment, dict)
            else None
        )
        or (
            raw_installment.get("current")
            if isinstance(raw_installment, dict)
            else None
        )
    )

    total = (
        item.get("installment_total")
        or item.get("total_installments")
        or (
            raw_installment.get("total")
            if isinstance(raw_installment, dict)
            else None
        )
        or (
            raw_installment.get("count")
            if isinstance(raw_installment, dict)
            else None
        )
    )

    if number is None or total is None:
        match = re.search(
            r"(?<!\d)(\d{1,2})\s*(?:/|de)\s*(\d{1,2})(?!\d)",
            description,
            flags=re.IGNORECASE,
        )
        if match:
            number = number or match.group(1)
            total = total or match.group(2)

    try:
        number = int(number) if number is not None else None
    except (TypeError, ValueError):
        number = None

    try:
        total = int(total) if total is not None else None
    except (TypeError, ValueError):
        total = None

    if (
        number is None
        or total is None
        or total <= 1
        or number < 1
        or number > total
    ):
        number = None
        total = None

    purchase_id = (
        item.get("purchase_id")
        or item.get("original_transaction_id")
        or item.get("credit_card_transaction_id")
        or item.get("merchant_transaction_id")
    )

    purchase_date = (
        item.get("purchase_date")
        or item.get("original_date")
    )

    if isinstance(purchase_date, str):
        try:
            purchase_date = datetime.fromisoformat(
                purchase_date.replace("Z", "+00:00")
            )
        except ValueError:
            purchase_date = None

    return {
        "installment_number": number,
        "installment_total": total,
        "purchase_id": (
            str(purchase_id)
            if purchase_id is not None
            else None
        ),
        "purchase_date": purchase_date,
    }


def _looks_like_credit_card_account(
    *,
    category: Any,
    name: Any,
) -> bool:
    value = (
        f"{category or ''} {name or ''}"
        .strip()
        .lower()
    )

    return any(
        token in value
        for token in (
            "credit_card",
            "credit card",
            "cartão de crédito",
            "cartao de credito",
            "cartão",
            "cartao",
        )
    )


class OpenFinanceProvider(ABC):
    @abstractmethod
    def create_consent(self, connection): ...

    @abstractmethod
    def fetch_accounts(self, connection): ...

    @abstractmethod
    def fetch_transactions(self, connection, account): ...


class MockProvider(OpenFinanceProvider):
    """Open Finance local para desenvolvimento.

    Simula duas contas e um histórico bancário determinístico. O campo
    ``mock_sequence`` da conexão controla quantas novas transações já foram
    "recebidas", permitindo testar o monitoramento automático sem API externa.
    """

    INSTITUTION_ID = "mockbank_br"
    INSTITUTION_NAME = "Mock Bank Brasil"

    def create_consent(self, connection):
        return {"status": "active", "authorization_url": None}

    def fetch_accounts(self, connection):
        suffix = str(connection.user_id).zfill(4)[-4:]
        return [
            {
                "id": f"mock-checking-{connection.id}",
                "name": "Conta Corrente",
                "masked": f"***{suffix}",
                "institution_id": self.INSTITUTION_ID,
                "institution_name": self.INSTITUTION_NAME,
                "balance": 4860.75,
                "is_credit_card": False,
            },
            {
                "id": f"mock-credit-{connection.id}",
                "name": "Cartão de Crédito",
                "masked": f"**** {1000 + connection.user_id}",
                "institution_id": self.INSTITUTION_ID,
                "institution_name": self.INSTITUTION_NAME,
                "balance": None,
                "is_credit_card": True,
            },
        ]

    def fetch_transactions(self, connection, account):
        now = datetime.now(timezone.utc)
        checking = str(account.get("id", "")).startswith("mock-checking")
        base = [
            ("pix-salario", -5, "Salário", "3500.00", "credit", True),
            ("mercado", -4, "Supermercado", "-186.42", "debit", True),
            ("energia", -3, "Conta de energia", "-214.70", "debit", True),
            ("uber", -2, "Uber", "-32.90", "debit", False),
            ("streaming", -1, "Assinatura streaming", "-39.90", "debit", False),
            ("farmacia", 0, "Farmácia", "-58.35", "debit", False),
        ]
        rows = []
        for key, days, desc, amount, kind, is_checking in base:
            if is_checking != checking:
                continue
            rows.append({"id": f"mock-{connection.id}-{key}", "date": now + timedelta(days=days), "description": desc, "amount": amount, "type": kind, "status": "posted"})

        # Cada incremento gera uma nova transação, alternando entre as contas.
        seq = int(getattr(connection, "mock_sequence", 0) or 0)
        merchants = ["Padaria Central", "Posto de combustível", "Restaurante", "PIX recebido", "Loja online", "Estacionamento"]
        amounts = ["-24.80", "-200.00", "-76.50", "150.00", "-129.99", "-18.00"]
        for i in range(1, seq + 1):
            belongs_checking = i % 2 == 1
            if belongs_checking != checking:
                continue
            amount = amounts[(i - 1) % len(amounts)]
            rows.append({"id": f"mock-{connection.id}-live-{i}", "date": now - timedelta(minutes=max(seq-i, 0)), "description": merchants[(i - 1) % len(merchants)], "amount": amount, "type": "credit" if not amount.startswith("-") else "debit", "status": "posted"})
        if not checking:
            purchase_date = now - timedelta(days=2)

            for installment_number in range(1, 5):
                charge_date = (
                    purchase_date
                    + timedelta(
                        days=30 * installment_number
                    )
                )

                rows.append(
                    {
                        "id": (
                            f"mock-{connection.id}-notebook-"
                            f"{installment_number}"
                        ),
                        "date": charge_date,
                        "description": (
                            f"Notebook {installment_number}/4"
                        ),
                        "amount": "-250.00",
                        "type": "debit",
                        "status": "posted",
                        "installment_number":
                            installment_number,
                        "installment_total": 4,
                        "purchase_id": (
                            f"mock-purchase-{connection.id}-notebook"
                        ),
                        "purchase_date": purchase_date,
                    }
                )

        return rows


# Alias legado: rotas antigas que pedirem "demo" continuam funcionando.
DemoProvider = MockProvider


class BelvoProvider(OpenFinanceProvider):
    """Adapter Belvo para Open Finance Brasil.

    O Hosted Widget cria o Link/consentimento. Depois usamos esse link para
    consultar Accounts e Transactions. As credenciais da Belvo ficam apenas
    no backend e nunca devem ser enviadas ao aplicativo Android.
    """

    def __init__(self):
        raw_base_url = (
            settings.belvo_base_url
            or "https://sandbox.belvo.com"
        ).rstrip("/")

        # Aceita tanto https://sandbox.belvo.com quanto
        # https://sandbox.belvo.com/api no .env sem gerar /api/api/...
        if raw_base_url.endswith("/api"):
            raw_base_url = raw_base_url[:-4]

        self.base_url = raw_base_url.rstrip("/")
        self.secret_id = settings.belvo_secret_id or ""
        self.secret_password = settings.belvo_secret_password or ""
        if not self.secret_id or not self.secret_password:
            raise RuntimeError(
                "BELVO_SECRET_ID e BELVO_SECRET_PASSWORD não configurados no .env"
            )

    @property
    def _basic_auth(self) -> httpx.BasicAuth:
        return httpx.BasicAuth(self.secret_id, self.secret_password)

    @staticmethod
    def _results(response_data: Any) -> list[dict[str, Any]]:
        if isinstance(response_data, list):
            return response_data
        if isinstance(response_data, dict):
            return response_data.get("results") or []
        return []

    def _get_all_pages(self, path: str, params: dict[str, Any]) -> list[dict[str, Any]]:
        """Percorre paginação Belvo sem depender do formato absoluto de `next`."""
        rows: list[dict[str, Any]] = []
        page = 1
        while True:
            current_params = dict(params)
            current_params.setdefault("page_size", 1000)
            current_params["page"] = page
            with httpx.Client(timeout=60.0, auth=self._basic_auth) as client:
                response = client.get(f"{self.base_url}{path}", params=current_params)
                response.raise_for_status()
                data = response.json()
            rows.extend(self._results(data))
            if not isinstance(data, dict) or not data.get("next"):
                break
            page += 1
        return rows

    async def create_widget_token(
            self,
            user_id: int,
            workspace_id: str,
            cpf: str,
            name: str,
    ) -> dict[str, Any]:

        payload = {
            "id": self.secret_id,
            "password": self.secret_password,

            "scopes": (
                "read_institutions,"
                "write_links,"
                "read_consents,"
                "write_consents,"
                "write_consent_callback,"
                "delete_consents"
            ),

            "fetch_resources": [
                "ACCOUNTS",
                "TRANSACTIONS",
                "OWNERS",
                "BILLS",
            ],

            "stale_in": "300d",

            "widget": {
                "purpose": (
                    "Organização e consolidação das informações "
                    "financeiras do usuário."
                ),

                "openfinance_feature": "consent_link_creation",

                "callback_urls": {
                    "success": "financeapp://success",
                    "exit": "financeapp://exit",
                    "event": "financeapp://error",
                },

                "consent": {
                    "terms_and_conditions_url": (
                        "https://seusite.com/termos"
                    ),

                    "permissions": [
                        "REGISTER",
                        "ACCOUNTS",
                        "CREDIT_CARDS",
                        "CREDIT_OPERATIONS",
                    ],

                    "default_consent_duration_days": 366,
                },

                "branding": {
                    "company_name": "Finance App",
                    "company_terms_url": (
                        "https://belvo.com/terms-service/"
                    ),
                },
            },
        }

        if cpf and name:
            payload["widget"]["consent"][
                "identification_info"
            ] = [
                {
                    "type": "CPF",
                    "number": cpf,
                    "name": name,
                }
            ]

        async with httpx.AsyncClient(
                timeout=30,
                auth=self._basic_auth,
        ) as client:
            response = await client.post(
                f"{self.base_url}/api/token/",
                json=payload,
            )

            if response.is_error:
                body = response.text

                raise RuntimeError(
                    "Belvo rejeitou a criação do widget "
                    f"({response.status_code}) em "
                    f"{self.base_url}/api/token/: "
                    f"{body[:700]}"
                )

            result = response.json()

            # identificador interno para usar no widget
            result["external_id"] = f"user_{user_id}_workspace_{workspace_id}"

            return result

    def create_consent(self, connection):
        # Na Belvo o consentimento é iniciado no Hosted Widget. Mantemos este
        # método por compatibilidade com a interface da V4.
        return {"status": "pending", "authorization_url": None}

    def get_link(self, link_id: str) -> dict[str, Any]:
        with httpx.Client(timeout=30.0, auth=self._basic_auth) as client:
            response = client.get(f"{self.base_url}/api/links/{link_id}/")
            response.raise_for_status()
            return response.json()

    def fetch_accounts(self, connection):
        if not connection.external_connection_id:
            return []
        raw = self._get_all_pages(
            "/api/accounts/", {"link": connection.external_connection_id}
        )
        normalized: list[dict[str, Any]] = []
        for item in raw:
            institution = item.get("institution") or {}
            category = item.get("category") or item.get("type")
            masked = (
                item.get("number")
                or item.get("masked_number")
                or item.get("account_number")
            )
            normalized.append(
                {
                    "id": item.get("id"),
                    "name": item.get("name") or item.get("display_name") or category,
                    "masked": str(masked)[-6:] if masked else None,
                    "institution_id": institution.get("name") or institution.get("id"),
                    "institution_name": institution.get("display_name") or institution.get("name"),
                    "balance": (
                        None
                        if _looks_like_credit_card_account(
                            category=category,
                            name=(
                                item.get("name")
                                or item.get("display_name")
                            ),
                        )
                        else _normalize_balance_value(
                            item.get("balance")
                            or item.get("current_balance")
                            or item.get("available_balance")
                        )
                    ),
                    "is_credit_card":
                        _looks_like_credit_card_account(
                            category=category,
                            name=(
                                item.get("name")
                                or item.get("display_name")
                            ),
                        ),
                    "raw": item,
                }
            )
        return [x for x in normalized if x.get("id")]

    def fetch_transactions(self, connection, account):
        if not connection.external_connection_id:
            return []
        params: dict[str, Any] = {"link": connection.external_connection_id}
        # Belvo permite filtrar pelo id da conta; se o contrato mudar, o link
        # ainda restringe o conjunto ao usuário correto.
        if account.get("id"):
            params["account"] = account["id"]
        raw = self._get_all_pages("/api/transactions/", params)

        normalized: list[dict[str, Any]] = []
        for item in raw:
            remote_account = item.get("account") or {}
            remote_account_id = remote_account.get("id") if isinstance(remote_account, dict) else None
            if account.get("id") and remote_account_id and str(remote_account_id) != str(account["id"]):
                continue

            date_value = (
                item.get("value_date")
                or item.get("accounting_date")
                or item.get("created_at")
            )
            if isinstance(date_value, str):
                try:
                    date_value = datetime.fromisoformat(date_value.replace("Z", "+00:00"))
                except ValueError:
                    date_value = datetime.fromisoformat(f"{date_value}T00:00:00+00:00")

            description = (
                item.get("description")
                or item.get("merchant_name")
                or item.get("reference")
                or "Transação bancária"
            )
            tx_type = (
                item.get("type")
                or item.get("transaction_type")
                or "unknown"
            )

            installment = _extract_installment_metadata(
                item,
                description,
            )

            normalized.append(
                {
                    "id": (
                        item.get("id")
                        or item.get(
                            "internal_identification"
                        )
                    ),
                    "date": date_value,
                    "description": description,
                    "amount": item.get("amount") or 0,
                    "type": str(tx_type),
                    "status": (
                        item.get("status")
                        or "posted"
                    ),
                    **installment,
                    "raw": item,
                }
            )
        return [x for x in normalized if x.get("id") and x.get("date")]

    def delete_link(self, link_id: str) -> bool:
        with httpx.Client(timeout=30.0, auth=self._basic_auth) as client:
            response = client.delete(f"{self.base_url}/api/links/{link_id}/")
            if response.status_code in {200, 202, 204}:
                return True
            response.raise_for_status()
            return False


def get_provider(name: str | None = None) -> OpenFinanceProvider:
    provider_name = (name or settings.openfinance_provider).strip().lower()
    if provider_name in {"demo", "mock"}:
        return MockProvider()
    if provider_name == "belvo":
        return BelvoProvider()
    raise NotImplementedError(f"Provedor Open Finance '{provider_name}' ainda não configurado")

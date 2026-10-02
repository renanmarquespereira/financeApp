import smtplib
from email.message import EmailMessage
import html

from app.core.config import settings


def send_account_deletion_code(
    recipient: str,
    code: str,
    institution_name: str,
) -> None:
    if (
        not settings.smtp_host
        or not settings.smtp_username
        or not settings.smtp_password
    ):
        raise RuntimeError(
            "SMTP não configurado. Defina SMTP_HOST, SMTP_USERNAME e SMTP_PASSWORD."
        )

    sender = settings.smtp_from or settings.smtp_username

    message = EmailMessage()
    message["Subject"] = "Código para excluir banco - Finance App"
    message["From"] = sender
    message["To"] = recipient

    message.set_content(
        f"""Olá,

Foi solicitada a exclusão do banco/conta "{institution_name}" no Finance App.

Seu código de confirmação é:

{code}

O código é válido por 10 minutos.

Se você não solicitou esta exclusão, não informe este código a ninguém e nenhuma conta será apagada.

Finance App
"""
    )

    if settings.smtp_port == 465:
        with smtplib.SMTP_SSL(
            settings.smtp_host,
            settings.smtp_port,
            timeout=20,
        ) as smtp:
            smtp.login(
                settings.smtp_username,
                settings.smtp_password,
            )
            smtp.send_message(message)
    else:
        with smtplib.SMTP(
            settings.smtp_host,
            settings.smtp_port,
            timeout=20,
        ) as smtp:
            smtp.ehlo()
            if settings.smtp_use_tls:
                smtp.starttls()
                smtp.ehlo()
            smtp.login(
                settings.smtp_username,
                settings.smtp_password,
            )
            smtp.send_message(message)



def send_user_data_deletion_code(
    recipient: str,
    code: str,
) -> None:
    if (
        not settings.smtp_host
        or not settings.smtp_username
        or not settings.smtp_password
    ):
        raise RuntimeError(
            "SMTP não configurado. Defina SMTP_HOST, SMTP_USERNAME e SMTP_PASSWORD."
        )

    sender = settings.smtp_from or settings.smtp_username

    message = EmailMessage()
    message["Subject"] = "Código para apagar todos os dados - Finance App"
    message["From"] = sender
    message["To"] = recipient

    message.set_content(
        f"""Olá,

Foi solicitada a exclusão de todos os seus dados financeiros no Finance App.

Seu código de confirmação é:

{code}

O código é válido por 10 minutos.

A confirmação apagará bancos, transações, categorias, orçamentos, metas,
conexões Open Finance e o estado de backup/sincronização associado à sua conta.

Sua conta de login e seu e-mail serão preservados.

Se você não solicitou esta ação, não informe este código a ninguém.

Finance App
"""
    )

    if settings.smtp_port == 465:
        with smtplib.SMTP_SSL(
            settings.smtp_host,
            settings.smtp_port,
            timeout=20,
        ) as smtp:
            smtp.login(
                settings.smtp_username,
                settings.smtp_password,
            )
            smtp.send_message(message)
    else:
        with smtplib.SMTP(
            settings.smtp_host,
            settings.smtp_port,
            timeout=20,
        ) as smtp:
            smtp.ehlo()
            if settings.smtp_use_tls:
                smtp.starttls()
                smtp.ehlo()
            smtp.login(
                settings.smtp_username,
                settings.smtp_password,
            )
            smtp.send_message(message)



def send_registration_verification_link(
    recipient: str,
    verification_url: str,
) -> None:
    if (
        not settings.smtp_host
        or not settings.smtp_username
        or not settings.smtp_password
    ):
        raise RuntimeError(
            "SMTP não configurado. Defina SMTP_HOST, "
            "SMTP_USERNAME e SMTP_PASSWORD."
        )

    sender = (
        settings.smtp_from
        or settings.smtp_username
    )

    message = EmailMessage()
    message["Subject"] = (
        "Confirme seu e-mail - Finance App"
    )
    message["From"] = sender
    message["To"] = recipient

    message.set_content(
        f"""Olá,

Recebemos uma solicitação para criar uma conta no Finance App.

Para confirmar seu e-mail, abra o link abaixo:

{verification_url}

O link é válido por 24 horas.

Se você não solicitou este cadastro, ignore este e-mail.

Finance App
"""
    )

    safe_url = html.escape(
        verification_url,
        quote=True,
    )

    message.add_alternative(
        f"""<!doctype html>
<html>
  <body style="font-family:Arial,sans-serif;background:#f6f7f9;padding:24px;">
    <div style="max-width:520px;margin:auto;background:#ffffff;border-radius:16px;padding:28px;">
      <h2 style="margin-top:0;">Confirme seu e-mail</h2>
      <p>Recebemos uma solicitação para criar uma conta no Finance App.</p>
      <p>Clique no botão abaixo para confirmar seu e-mail:</p>
      <p style="text-align:center;margin:28px 0;">
        <a href="{safe_url}"
           style="background:#4f46e5;color:#ffffff;text-decoration:none;padding:14px 24px;border-radius:10px;display:inline-block;font-weight:700;">
          Confirmar meu e-mail
        </a>
      </p>
      <p style="font-size:13px;color:#666666;">Este link é válido por 24 horas.</p>
      <p style="font-size:13px;color:#666666;">Se você não solicitou este cadastro, ignore este e-mail.</p>
    </div>
  </body>
</html>""",
        subtype="html",
    )

    if settings.smtp_port == 465:
        with smtplib.SMTP_SSL(
            settings.smtp_host,
            settings.smtp_port,
            timeout=20,
        ) as smtp:
            smtp.login(
                settings.smtp_username,
                settings.smtp_password,
            )
            smtp.send_message(message)
    else:
        with smtplib.SMTP(
            settings.smtp_host,
            settings.smtp_port,
            timeout=20,
        ) as smtp:
            smtp.ehlo()
            if settings.smtp_use_tls:
                smtp.starttls()
                smtp.ehlo()
            smtp.login(
                settings.smtp_username,
                settings.smtp_password,
            )
            smtp.send_message(message)


def send_security_code(recipient: str, code: str, subject: str, action: str) -> None:
    if not settings.smtp_host or not settings.smtp_username or not settings.smtp_password:
        raise RuntimeError('SMTP não configurado')
    message = EmailMessage()
    message['Subject'] = subject + ' - Finance App'
    message['From'] = settings.smtp_from or settings.smtp_username
    message['To'] = recipient
    message.set_content(f'Foi solicitado: {action}.\n\nCódigo: {code}\n\nVálido por 10 minutos, para uma única confirmação. Se não foi você, ignore esta mensagem e não compartilhe o código.')
    cls = smtplib.SMTP_SSL if settings.smtp_port == 465 else smtplib.SMTP
    with cls(settings.smtp_host, settings.smtp_port, timeout=20) as smtp:
        if settings.smtp_port != 465:
            smtp.ehlo()
            if settings.smtp_use_tls:
                smtp.starttls(); smtp.ehlo()
        smtp.login(settings.smtp_username, settings.smtp_password)
        smtp.send_message(message)

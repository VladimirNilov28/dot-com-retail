import smtplib
from email.message import EmailMessage


class MailDeliveryError(RuntimeError):
    """Bounded failure reason; never carries addresses or message content."""


class SmtpMailer:
    """Plain SMTP without STARTTLS, mirroring the dev Kratos courier
    (smtp://mailpit:1025/?disable_starttls=true)."""

    def __init__(self, host: str, port: int, sender: str):
        self._host = host
        self._port = port
        self._sender = sender

    def send(self, recipient: str, subject: str, body: str) -> None:
        message = EmailMessage()
        message["From"] = self._sender
        message["To"] = recipient
        message["Subject"] = subject
        message.set_content(body)
        try:
            with smtplib.SMTP(self._host, self._port, timeout=10) as smtp:
                smtp.send_message(message)
        except (smtplib.SMTPException, OSError) as error:
            raise MailDeliveryError(f"SMTP delivery failed ({type(error).__name__})") from None

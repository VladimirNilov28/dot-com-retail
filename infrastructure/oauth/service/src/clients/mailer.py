import smtplib
import ssl
from email.message import EmailMessage


class MailDeliveryError(RuntimeError):
    """Bounded failure reason; never carries addresses or message content."""


class SmtpMailer:
    """SMTP delivery. Defaults mirror the dev Kratos courier (plain SMTP to
    Mailpit); production enables STARTTLS or implicit TLS and credentials."""

    def __init__(
        self,
        host: str,
        port: int,
        sender: str,
        username: str = "",
        password: str = "",
        starttls: bool = False,
        implicit_tls: bool = False,
    ):
        self._host = host
        self._port = port
        self._sender = sender
        self._username = username
        self._password = password
        self._starttls = starttls
        self._implicit_tls = implicit_tls

    def send(self, recipient: str, subject: str, body: str) -> None:
        message = EmailMessage()
        message["From"] = self._sender
        message["To"] = recipient
        message["Subject"] = subject
        message.set_content(body)
        try:
            if self._implicit_tls:
                connection = smtplib.SMTP_SSL(
                    self._host, self._port, timeout=10, context=ssl.create_default_context()
                )
            else:
                connection = smtplib.SMTP(self._host, self._port, timeout=10)
            with connection as smtp:
                if self._starttls:
                    smtp.starttls(context=ssl.create_default_context())
                if self._username:
                    smtp.login(self._username, self._password)
                smtp.send_message(message)
        except (smtplib.SMTPException, OSError) as error:
            raise MailDeliveryError(f"SMTP delivery failed ({type(error).__name__})") from None

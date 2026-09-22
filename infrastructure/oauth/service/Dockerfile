FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY src/ /app/src/

EXPOSE 4446

ENTRYPOINT ["python", "/app/src/main.py"]

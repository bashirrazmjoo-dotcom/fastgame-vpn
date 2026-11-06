FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv
COPY requirements.lock .
RUN pip install --no-cache-dir -r requirements.lock
COPY alembic.ini .
COPY alembic ./alembic
COPY app ./app
RUN useradd -r -u 10001 fastgame
USER fastgame
EXPOSE 8000
# --proxy-headers: the client IP comes from Nginx (only Nginx can reach this port).
CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000 --proxy-headers --forwarded-allow-ips='*' --workers 2"]

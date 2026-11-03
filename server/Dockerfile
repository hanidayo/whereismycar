FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 DATA_DIR=/data TZ=Asia/Seoul
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
RUN useradd -u 1000 -m eodi && mkdir -p /data && chown eodi /data
USER eodi
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]

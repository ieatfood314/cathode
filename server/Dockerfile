FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg curl ca-certificates unzip \
 && rm -rf /var/lib/apt/lists/* \
 && pip install --no-cache-dir yt-dlp \
 && curl -fsSL https://deno.land/install.sh | sh \
 && ln -s /root/.deno/bin/deno /usr/local/bin/deno \
 && deno --version && yt-dlp --version

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app

VOLUME ["/data", "/media"]
EXPOSE 8000
ENV RUMINATE_DATA=/data RUMINATE_MEDIA=/media RUMINATE_DB_PATH=/data/ruminate.db \
    RUMINATE_ARCHIVE_PATH=/data/archive.txt
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

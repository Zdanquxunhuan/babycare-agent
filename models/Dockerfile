FROM python:3.11-slim
WORKDIR /service
ENV HF_HOME=/data/huggingface HF_HUB_DISABLE_XET=1 HF_HUB_DOWNLOAD_TIMEOUT=120 TOKENIZERS_PARALLELISM=false
RUN pip install --no-cache-dir onnxruntime==1.22.1 numpy==2.2.6 tokenizers==0.21.4 huggingface-hub==0.34.4 fastapi==0.116.1 uvicorn==0.35.0
COPY service.py .
CMD ["uvicorn","service:app","--host","0.0.0.0","--port","8080"]

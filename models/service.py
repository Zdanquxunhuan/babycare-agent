"""Local, quantized CPU inference for the V1.0b comparison."""
from contextlib import asynccontextmanager
from threading import Lock

import numpy as np
import onnxruntime as ort
from fastapi import FastAPI
from huggingface_hub import hf_hub_download
from pydantic import BaseModel, Field
from tokenizers import Tokenizer


class Encoder:
    def __init__(self, repo, revision):
        def fetch(name):
            return hf_hub_download(repo_id=repo, filename=name, revision=revision)

        self.tokenizer = Tokenizer.from_file(fetch("tokenizer.json"))
        self.tokenizer.enable_truncation(max_length=512)
        self.tokenizer.enable_padding(pad_id=self.tokenizer.token_to_id("<pad>"), pad_token="<pad>")
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(fetch("onnx/model_int8.onnx"), options,
                                            providers=["CPUExecutionProvider"])

    def infer(self, inputs):
        encoded = self.tokenizer.encode_batch(inputs)
        fields = {"input_ids": "ids", "attention_mask": "attention_mask", "token_type_ids": "type_ids"}
        arrays = {node.name: np.asarray([getattr(item, fields[node.name]) for item in encoded], dtype=np.int64)
                  for node in self.session.get_inputs()}
        return self.session.run(None, arrays)[0]


@asynccontextmanager
async def lifespan(app):
    app.state.embed = Encoder("Xenova/bge-m3", "46ce10a0ac77ca4bab460d6676ba8260b33e68dc")
    app.state.rank = Encoder("onnx-community/bge-reranker-v2-m3-ONNX", "90213ffc6a8e6f051a6331269a0f5526cdd896f6")
    yield


app = FastAPI(lifespan=lifespan)
inference = Lock()


class Batch(BaseModel):
    inputs: list[str] = Field(min_length=1, max_length=8)


class Pairs(BaseModel):
    query: str
    texts: list[str] = Field(min_length=1, max_length=10)


@app.get("/health")
def health():
    return {"ready": True}


@app.post("/embed")
def embed(batch: Batch):
    with inference:
        hidden = app.state.embed.infer(batch.inputs)
        cls = hidden[:, 0, :]
        return (cls / np.linalg.norm(cls, axis=1, keepdims=True)).tolist()


@app.post("/rerank")
def rerank(batch: Pairs):
    with inference:
        logits = app.state.rank.infer([(batch.query, text) for text in batch.texts]).reshape(-1)
        return [{"index": i, "score": float(score)} for i, score in enumerate(logits)]

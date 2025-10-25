#!/usr/bin/env python3

import os
from typing import List

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

#can be overridden with environment variable EMBEDDING_MODEL
MODEL_NAME = os.getenv("EMBEDDING_MODEL", "sentence-transformers/all-MiniLM-L6-v2")
app = FastAPI(title="Sentence-Transformers all-MiniLM-L6-v2 embedding local server", version="1.0")

# Lazy-loading the transformer to keep startup fast when imported
_model = None

def get_model() -> SentenceTransformer:
    global _model
    if _model is None:
        _model = SentenceTransformer(MODEL_NAME)
    return _model


class EmbeddingRequest(BaseModel):
    input: str


class EmbeddingData(BaseModel):
    embedding: List[float]


class EmbeddingResponse(BaseModel):
    data: List[EmbeddingData]

# Embedding endpoint, using a local model for this since most LLM API providers don't offer free embedding endpoints
@app.post("/v1/embeddings", response_model=EmbeddingResponse)
def generate_embeddings(request: EmbeddingRequest) -> EmbeddingResponse:
    text = request.input.strip()
    if not text:
        raise HTTPException(status_code=400, detail="Input must not be empty")

    model = get_model()
    raw_vector = model.encode(text, convert_to_numpy=True)
    vector = raw_vector.tolist() if hasattr(raw_vector, "tolist") else list(raw_vector)
    return EmbeddingResponse(data=[EmbeddingData(embedding=vector)])

# Status endpoint
@app.get("/healthz")
def healthcheck() -> dict:
    return {"status": "ok", "model": MODEL_NAME}

#Simple landing route to keep logs clean when someone curls "/"
@app.get("/")
def landing() -> dict:
    return {"status": "ok", "message": "Use POST /v1/embeddings for embeddings"}

#Return 204 so favicon probes don't spam 404s
@app.get("/favicon.ico", status_code=204)
def favicon():
    return None

if __name__ == "__main__":
    import uvicorn

    port = int(os.getenv("EMBEDDING_PORT", "11435"))
    uvicorn.run("local_embedding_server:app", host="127.0.0.1", port=port, reload=False)

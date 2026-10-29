from fastapi import FastAPI

from app.routers import items, uploads, users

app = FastAPI()
app.include_router(items.router, prefix="/items")
app.include_router(users.router, prefix="/users")
app.include_router(uploads.router)


@app.get("/health")
def health():
    return {"ok": True}

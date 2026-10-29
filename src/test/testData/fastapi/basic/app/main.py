from fastapi import FastAPI

from .routers import items, users
from .sub import sub

app = FastAPI()
app.include_router(items.router, prefix="/api/v1")
app.include_router(items.router, prefix="/api/latest")
app.include_router(users.users_router, prefix="/api/v1", tags=["v1"])
app.mount("/sub", sub)


@app.get("/health")
async def health():
    return {"ok": True}


@app.api_route("/multi", methods=["GET", "POST"])
def multi():
    ...


@app.websocket("/ws")
async def ws(websocket):
    ...

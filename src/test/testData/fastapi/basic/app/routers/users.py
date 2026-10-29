from fastapi import APIRouter

from . import admin

users_router = APIRouter(prefix="/users")


@users_router.get("/me")
def me():
    ...


users_router.include_router(admin.admin_router, prefix="/admin", tags=["admin"], deprecated=True)

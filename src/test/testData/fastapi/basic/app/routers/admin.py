from fastapi import APIRouter

admin_router = APIRouter(tags=["admin-internal"])


@admin_router.delete("/{user_id}")
def delete_user(user_id: int):
    ...

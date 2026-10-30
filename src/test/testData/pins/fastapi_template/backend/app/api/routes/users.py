from fastapi import APIRouter

router = APIRouter(prefix="/users", tags=["users"])


@router.get("/")
def read_users(): ...


@router.post("/")
def create_user(): ...


@router.get("/me")
def read_user_me(): ...


@router.delete("/{user_id}")
def delete_user(user_id: int): ...

from fastapi import APIRouter

router = APIRouter(tags=["login"])


@router.post("/login/access-token")
def login_access_token(): ...


@router.post("/login/test-token")
def test_token(): ...


@router.post("/password-recovery/{email}")
def recover_password(email: str): ...

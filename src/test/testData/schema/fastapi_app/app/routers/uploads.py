from typing import Annotated

from fastapi import APIRouter, Form, UploadFile

router = APIRouter()


@router.post("/upload")
def upload(file: UploadFile):
    return {"name": file.filename}


@router.post("/login")
def login(username: Annotated[str, Form()], password: Annotated[str, Form()]):
    return {"user": username}

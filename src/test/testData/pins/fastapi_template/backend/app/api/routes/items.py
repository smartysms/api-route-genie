from fastapi import APIRouter

router = APIRouter(prefix="/items", tags=["items"])


@router.get("/")
def read_items(): ...


@router.get("/{id}")
def read_item(id: int): ...


@router.put("/{id}")
def update_item(id: int): ...

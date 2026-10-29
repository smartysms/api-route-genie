from fastapi import APIRouter

router = APIRouter(prefix="/items", tags=["items"])


@router.get("/")
def list_items():
    ...


@router.get("/{item_id}")
def read_item(item_id: int, q: str = None):
    ...


@router.post("/", status_code=201, response_model=dict)
def create_item(body: dict):
    ...

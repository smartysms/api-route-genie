from typing import List

from fastapi import APIRouter

from app.schemas.item import Item, ItemUpdate

router = APIRouter()


@router.get("/")
def list_items(limit: int = 10):
    return []


@router.post("/")
def create_item(item: Item):
    return item


@router.put("/{item_id}")
def replace_item(item_id: int, item: Item):
    return item


@router.patch("/{item_id}")
def update_item(item_id: int, item: ItemUpdate):
    return item


@router.post("/bulk")
def create_many(items: List[Item]):
    return items


@router.delete("/{item_id}")
def delete_item(item_id: int):
    return None

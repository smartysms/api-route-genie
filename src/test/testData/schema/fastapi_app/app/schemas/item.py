from enum import Enum
from typing import List, Optional

from pydantic import BaseModel, Field

from .dimensions import Dimensions


class Category(str, Enum):
    BOOK = "book"
    TOY = "toy"


class Item(BaseModel):
    name: str = Field(min_length=1, max_length=50)
    price: float = Field(gt=0)
    category: Category
    tags: List[str] = []
    dimensions: Dimensions
    note: Optional[str] = None


class ItemUpdate(BaseModel):
    name: Optional[str] = None
    price: Optional[float] = Field(None, gt=0)

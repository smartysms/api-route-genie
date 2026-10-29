from typing import List, Optional

from pydantic.v1 import BaseModel, Field


class User(BaseModel):
    user_id: int = Field(alias="userId")
    email: Optional[str]
    nickname: str = Field("anon", min_length=3)

    class Config:
        allow_population_by_field_name = True


class Tree(BaseModel):
    value: int
    children: List["Tree"] = []

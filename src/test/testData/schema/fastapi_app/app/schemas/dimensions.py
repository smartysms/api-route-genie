from typing import Literal

from pydantic import BaseModel


class Dimensions(BaseModel):
    width: float
    height: float
    unit: Literal["cm", "in"] = "cm"

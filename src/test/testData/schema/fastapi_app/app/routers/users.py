from fastapi import APIRouter

from app.schemas.user import Tree, User

router = APIRouter()


@router.post("/")
def create_user(user: User):
    return user


@router.post("/tree")
def create_tree(tree: Tree):
    return tree

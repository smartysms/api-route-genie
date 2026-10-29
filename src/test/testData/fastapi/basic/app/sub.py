from fastapi import FastAPI

sub = FastAPI()


@sub.get("/ping")
def ping():
    ...

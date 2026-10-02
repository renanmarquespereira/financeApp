from pydantic import BaseModel


class CategoryCreate(BaseModel):
    name: str
    icon: str | None = None


class CategoryUpdate(BaseModel):
    name: str
    icon: str | None = None


class CategoryResponse(BaseModel):
    workspace_id: str
    id: int
    name: str
    icon: str | None = None

    class Config:
        from_attributes = True
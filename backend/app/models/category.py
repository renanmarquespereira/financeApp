from app.models.workspace import WorkspaceOwned
from sqlalchemy import String, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.database.database import Base

class Category(WorkspaceOwned, Base):
    __tablename__="categories"
    id: Mapped[int]=mapped_column(primary_key=True)
    user_id: Mapped[int]=mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    name: Mapped[str]=mapped_column(String(100))
    icon: Mapped[str|None]=mapped_column(String(20))
    user=relationship("User", back_populates="categories")
    transactions=relationship("Transaction", back_populates="category")

from pathlib import Path
from uuid import uuid4
from fastapi import APIRouter, Depends, HTTPException, UploadFile, File
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session
from app.core.workspace import get_workspace_db as get_db, scope_id
from app.core.security import get_current_user
from app.models.user import User
from app.models.transaction import Transaction
from app.models.transaction_attachment import TransactionAttachment

router = APIRouter()
ROOT = Path(__file__).resolve().parents[2] / "data" / "transaction_attachments"
ROOT.mkdir(parents=True, exist_ok=True)
ALLOWED = {"image/jpeg", "image/png", "application/pdf"}
MAX_BYTES = 15 * 1024 * 1024

def _tx(db, user, transaction_id):
    obj = db.query(Transaction).filter(Transaction.id == transaction_id, Transaction.user_id == user.id).first()
    if not obj: raise HTTPException(404, "Transação não encontrada")
    return obj

def _json(a):
    return {"id": a.id, "transaction_id": a.transaction_id, "name": a.original_name, "content_type": a.content_type, "size_bytes": a.size_bytes, "created_at": a.created_at}

@router.get("/transactions/{transaction_id}/attachments")
def list_attachments(transaction_id:int, db:Session=Depends(get_db), user:User=Depends(get_current_user)):
    _tx(db,user,transaction_id)
    rows=db.query(TransactionAttachment).filter(TransactionAttachment.user_id==user.id,TransactionAttachment.transaction_id==transaction_id).order_by(TransactionAttachment.id.desc()).all()
    return [_json(a) for a in rows]

@router.post("/transactions/{transaction_id}/attachments")
async def upload_attachment(transaction_id:int, file:UploadFile=File(...), db:Session=Depends(get_db), user:User=Depends(get_current_user)):
    _tx(db,user,transaction_id)
    content_type=(file.content_type or "application/octet-stream").lower()
    if content_type not in ALLOWED: raise HTTPException(415,"Use JPG, PNG ou PDF.")
    data=await file.read(MAX_BYTES+1)
    if len(data)>MAX_BYTES: raise HTTPException(413,"O comprovante deve ter no máximo 15 MB.")
    ext={"image/jpeg":".jpg","image/png":".png","application/pdf":".pdf"}[content_type]
    storage=f"{uuid4().hex}{ext}"
    (ROOT/storage).write_bytes(data)
    row=TransactionAttachment(user_id=user.id,workspace_id=scope_id(db),transaction_id=transaction_id,original_name=(file.filename or f"comprovante{ext}")[:255],content_type=content_type,size_bytes=len(data),storage_name=storage)
    db.add(row); db.commit(); db.refresh(row)
    return _json(row)

@router.get("/transactions/{transaction_id}/attachments/{attachment_id}/download")
def download_attachment(transaction_id:int, attachment_id:int, db:Session=Depends(get_db), user:User=Depends(get_current_user)):
    _tx(db,user,transaction_id)
    row=db.query(TransactionAttachment).filter(TransactionAttachment.id==attachment_id,TransactionAttachment.user_id==user.id,TransactionAttachment.transaction_id==transaction_id).first()
    if not row: raise HTTPException(404,"Comprovante não encontrado")
    path=ROOT/row.storage_name
    if not path.exists(): raise HTTPException(404,"Arquivo do comprovante não encontrado")
    return FileResponse(path,media_type=row.content_type,filename=row.original_name)

@router.delete("/transactions/{transaction_id}/attachments/{attachment_id}")
def delete_attachment(transaction_id:int, attachment_id:int, db:Session=Depends(get_db), user:User=Depends(get_current_user)):
    _tx(db,user,transaction_id)
    row=db.query(TransactionAttachment).filter(TransactionAttachment.id==attachment_id,TransactionAttachment.user_id==user.id,TransactionAttachment.transaction_id==transaction_id).first()
    if not row: raise HTTPException(404,"Comprovante não encontrado")
    path=ROOT/row.storage_name
    db.delete(row); db.commit()
    try: path.unlink(missing_ok=True)
    except OSError: pass
    return {"status":"deleted"}

from celery import Celery
from app.core.config import settings

celery_app = Celery(
    "finance_app",
    broker=settings.redis_url,
    backend=settings.redis_url,
    include=["app.workers.tasks"],
)

celery_app.conf.timezone = "America/Sao_Paulo"

celery_app.conf.beat_schedule = {
    "enqueue-openfinance-syncs": {
        "task": "app.workers.tasks.enqueue_due_syncs",
        "schedule": 60.0,
    },
}

# Garante o registro das tasks também quando o módulo é carregado
# em contextos onde o include não é processado imediatamente.
celery_app.autodiscover_tasks(["app.workers"])

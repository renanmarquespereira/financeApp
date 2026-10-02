"""Append-only saved snapshots; explicit tombstones always win over stale devices."""
from decimal import Decimal, InvalidOperation


def canonical_id(value):
    if value is None or isinstance(value, bool):
        return None
    text = str(value)
    try:
        number = Decimal(text)
        if number.is_finite() and number == number.to_integral_value():
            return str(int(number))
    except InvalidOperation:
        pass
    return text or None


def merge_forecast_payload(current, incoming):
    merged = dict(current)
    for collection, tombstones in (("scenarios", "deletedScenarioIds"), ("aiPlans", "deletedAiPlanIds"), ("debts", "deletedDebtIds")):
        deleted = {canonical_id(v) for p in (current, incoming) for v in p.get(tombstones, [])}
        deleted.discard(None)
        items = {}
        # Saved scenarios/plans are immutable snapshots. Existing server copies win;
        # a change must create a new id, never overwrite a snapshot from stale cache.
        for p in (current, incoming):
            for item in p.get(collection, []):
                if not isinstance(item, dict):
                    continue
                key = canonical_id(item.get("id"))
                if key and key not in deleted and key not in items:
                    items[key] = item
        merged[collection] = sorted(items.values(), key=lambda v: canonical_id(v["id"]), reverse=True)
        merged[tombstones] = sorted(deleted)
    # Do not echo arbitrary stale client fields over other saved collections.
    return merged

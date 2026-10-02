from app.services.forecast_merge import merge_forecast_payload
from test_workspaces import client, engine, headers


def test_old_device_cannot_remove_or_overwrite_saved_items():
    current={"scenarios":[{"id":123,"name":"servidor"}],"aiPlans":[{"id":90,"content":"plano"}]}
    result=merge_forecast_payload(current,{"scenarios":[{"id":123.0,"name":"antigo"},{"id":124,"name":"novo"}],"aiPlans":[]})
    assert len(result["scenarios"])==2
    assert next(s for s in result["scenarios"] if s["id"]==123)["name"]=="servidor"
    assert result["aiPlans"]==current["aiPlans"]


def test_tombstones_win_even_after_retries_from_old_devices():
    initial={"scenarios":[{"id":100}],"aiPlans":[{"id":200}]}
    deleted=merge_forecast_payload(initial,{"deletedScenarioIds":[100.0],"deletedAiPlanIds":["200"]})
    replay=merge_forecast_payload(deleted,initial)
    assert replay["scenarios"]==[] and replay["aiPlans"]==[]
    assert replay["deletedScenarioIds"]==["100"]
    assert merge_forecast_payload(replay,initial)==replay


def test_endpoint_collections_workspace_and_user_isolation(client, engine):
    h=headers(workspace="company")
    assert client.get("/forecast-state",headers=h).json()["sync_version"]==2
    a=client.put("/forecast-state",headers=h,json={"payload":{"scenarios":[{"id":10,"name":"Android"}]}})
    assert a.status_code==200,a.text
    b=client.put("/forecast-state",headers=h,json={"payload":{"aiPlans":[{"id":20,"title":"iOS","content":"Plano"}]}})
    assert b.status_code==200,b.text
    assert len(b.json()["payload"]["scenarios"])==1
    assert len(b.json()["payload"]["aiPlans"])==1
    assert client.get("/forecast-state",headers=headers(workspace="default-1")).json()["payload"]=={}
    assert client.get("/forecast-state",headers=headers(2,"company")).status_code==404
    d=client.put("/forecast-state",headers=h,json={"payload":{"deletedAiPlanIds":[20.0]}})
    assert d.status_code==200
    r=client.put("/forecast-state",headers=h,json={"payload":{"aiPlans":[{"id":20,"content":"antigo"}]}})
    assert r.json()["payload"]["aiPlans"]==[]
    assert r.json()["payload"]["scenarios"][0]["name"]=="Android"

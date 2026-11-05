"""서버 API 기본 동작 테스트 (CI에서 실행): python test_api.py"""
import os
import tempfile

os.environ.setdefault("DATA_DIR", tempfile.mkdtemp())
os.environ.setdefault("FAMILY_TOKEN", "test-family-token-123")

from fastapi.testclient import TestClient  # noqa: E402

import app.main as m  # noqa: E402

c = TestClient(m.app)
H = {"Authorization": "Bearer test-family-token-123"}


def ok(r, code=200):
    assert r.status_code == code, (r.status_code, r.text)
    return r.json()


assert ok(c.get("/api/health"))["ok"]
assert c.get("/api/state").status_code == 401

ok(c.put("/api/cars/CAR-A", json={"name": "차1", "color": 0}, headers=H))
ok(c.put("/api/cars/car-b", json={"name": "차2", "color": 1}, headers=H))

# iOS(대문자 UUID·소수점 초) / Android(소문자) 섞여도 하나로
assert ok(c.post("/api/parkings", json={"id": "P-1", "car_id": "car-a", "member": "한스",
                                        "date": "2026-11-03T09:00:00.123Z", "level": -1,
                                        "course": 182.5},
                 headers=H))["created"]
assert not ok(c.post("/api/parkings", json={"id": "p-1", "car_id": "CAR-A", "member": "한스"},
                     headers=H))["created"]

# 층 보정 + 차 바꾸기
ok(c.patch("/api/parkings/P-1", json={"car_id": "CAR-B", "level": -2, "calibrated": True}, headers=H))
# car_id 없이(null) 보내도 차는 그대로
ok(c.patch("/api/parkings/p-1", json={"car_id": None, "pillar_text": "A-12", "calibrated": True}, headers=H))

st = ok(c.get("/api/state", headers=H))
p = st["parkings"][0]
assert p["car_id"] == "car-b" and p["level"] == -2 and p["pillar_text"] == "A-12", p
assert p["date"] == "2026-11-03T09:00:00Z", p["date"]
assert p["course"] == 182.5, p

ok(c.put("/api/parkings/p-1/photo", content=b"\xff\xd8test", headers=H))
assert c.get("/api/parkings/P-1/photo", headers=H).content == b"\xff\xd8test"

ok(c.put("/api/garages/G-1", json={"id": "G-1", "latitude": 37.5, "longitude": 127.0, "course": 10.0,
                                   "samples": [{"record_id": "P-1", "delta": 1.0, "level": -1}]},
         headers=H))
g = ok(c.get("/api/state", headers=H))["garages"][0]
assert g["samples"][0]["record_id"] == "p-1" and g["course"] == 10.0, g
# 방향 없이 다시 올려도 기존 방향 유지
ok(c.put("/api/garages/g-1", json={"latitude": 37.5, "longitude": 127.0, "name": "정문", "icon": "home",
                                   "samples": []}, headers=H))
g = ok(c.get("/api/state", headers=H))["garages"][0]
assert g["course"] == 10.0 and g["name"] == "정문" and g["icon"] == "home", g
# 아이콘 없이 다시 올려도 유지
ok(c.put("/api/garages/g-1", json={"latitude": 37.5, "longitude": 127.0, "name": "집 정문", "samples": []}, headers=H))
g = ok(c.get("/api/state", headers=H))["garages"][0]
assert g["icon"] == "home" and g["name"] == "집 정문", g

# 예전 DB(course 컬럼 없음)에서 시작해도 마이그레이션
import importlib, sqlite3, sys  # noqa: E401,E402
old = tempfile.mkdtemp()
con = sqlite3.connect(os.path.join(old, "eodi.sqlite3"))
con.executescript("""CREATE TABLE parkings (id TEXT PRIMARY KEY, car_id TEXT NOT NULL, member TEXT NOT NULL,
    date TEXT NOT NULL, latitude REAL, longitude REAL, accuracy REAL, delta_meters REAL, level INTEGER,
    calibrated INTEGER NOT NULL DEFAULT 0, pillar_text TEXT, ocr_level INTEGER,
    has_photo INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL);
    CREATE TABLE garages (id TEXT PRIMARY KEY, latitude REAL NOT NULL, longitude REAL NOT NULL, name TEXT,
    samples TEXT NOT NULL DEFAULT '[]', updated_at TEXT NOT NULL);""")
con.close()
os.environ["DATA_DIR"] = old
m.DATA_DIR  # noqa: B018
del sys.modules["app.main"]
m2 = importlib.import_module("app.main")
cols = {r[1] for r in m2._db.execute("PRAGMA table_info(garages)")}
assert {"course", "icon"} <= cols, cols

print("서버 테스트 통과")

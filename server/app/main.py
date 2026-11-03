"""어디댔지 가족 공유 서버.

가족 코드(FAMILY_TOKEN) 하나로 인증하고, 주차가 기록되면
등록된 안드로이드 기기에 FCM 푸시를 보냅니다. (ntfy는 선택)
"""
import json
import os
import secrets
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional
from zoneinfo import ZoneInfo

import httpx
from fastapi import BackgroundTasks, Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel

DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
PHOTO_DIR = DATA_DIR / "photos"
PHOTO_DIR.mkdir(parents=True, exist_ok=True)

FAMILY_TOKEN = os.environ.get("FAMILY_TOKEN", "")
NTFY_URL = os.environ.get("NTFY_URL", "https://ntfy.sh").rstrip("/")
NTFY_TOPIC = os.environ.get("NTFY_TOPIC", "")
NTFY_TOKEN = os.environ.get("NTFY_TOKEN", "")
FCM_CREDENTIALS = os.environ.get("FCM_CREDENTIALS", "/data/firebase-service-account.json")
APP_NAME = os.environ.get("APP_NAME", "어디댔지")
LOCAL_TZ = ZoneInfo(os.environ.get("TZ", "Asia/Seoul"))
MAX_PHOTO_BYTES = 6 * 1024 * 1024

if len(FAMILY_TOKEN) < 12:
    raise SystemExit("FAMILY_TOKEN 환경변수를 12자 이상으로 설정하세요.")

# ---------------------------------------------------------------- DB

_lock = threading.Lock()
_db = sqlite3.connect(DATA_DIR / "eodi.sqlite3", check_same_thread=False)
_db.row_factory = sqlite3.Row
_db.executescript(
    """
    CREATE TABLE IF NOT EXISTS cars (
        id TEXT PRIMARY KEY, name TEXT NOT NULL, color INTEGER NOT NULL DEFAULT 0,
        updated_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS parkings (
        id TEXT PRIMARY KEY, car_id TEXT NOT NULL, member TEXT NOT NULL, date TEXT NOT NULL,
        latitude REAL, longitude REAL, accuracy REAL, delta_meters REAL,
        level INTEGER, calibrated INTEGER NOT NULL DEFAULT 0,
        pillar_text TEXT, ocr_level INTEGER, has_photo INTEGER NOT NULL DEFAULT 0,
        updated_at TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS parkings_car_date ON parkings(car_id, date);
    CREATE TABLE IF NOT EXISTS devices (
        token TEXT PRIMARY KEY, member TEXT NOT NULL, platform TEXT NOT NULL,
        updated_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS garages (
        id TEXT PRIMARY KEY, latitude REAL NOT NULL, longitude REAL NOT NULL, name TEXT,
        samples TEXT NOT NULL DEFAULT '[]', updated_at TEXT NOT NULL);
    """
)


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def norm_date(value: Optional[str]) -> str:
    """앱마다 다른 ISO 형식을 초 단위 UTC 'Z' 형식으로 통일(iOS 디코더 호환)."""
    if not value:
        return now_iso()
    try:
        d = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return now_iso()
    if d.tzinfo is None:
        d = d.replace(tzinfo=timezone.utc)
    return d.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def nid(value: str) -> str:
    """iOS는 대문자, Android는 소문자 UUID → 소문자로 통일."""
    return value.strip().lower()


def level_name(level: Optional[int]) -> str:
    if level is None:
        return "층 미확인"
    return f"{level + 1}F" if level >= 0 else f"B{-level}"


def car_out(r: sqlite3.Row) -> dict:
    return {"id": r["id"], "name": r["name"], "color": r["color"]}


def parking_out(r: sqlite3.Row) -> dict:
    return {
        "id": r["id"], "car_id": r["car_id"], "member": r["member"], "date": r["date"],
        "latitude": r["latitude"], "longitude": r["longitude"], "accuracy": r["accuracy"],
        "delta_meters": r["delta_meters"], "level": r["level"],
        "calibrated": bool(r["calibrated"]), "pillar_text": r["pillar_text"],
        "ocr_level": r["ocr_level"], "has_photo": bool(r["has_photo"]),
    }


def garage_out(r: sqlite3.Row) -> dict:
    return {"id": r["id"], "latitude": r["latitude"], "longitude": r["longitude"],
            "name": r["name"], "samples": json.loads(r["samples"])}


# ---------------------------------------------------------------- 알림 (FCM + 선택 ntfy)

class FcmSender:
    """Firebase 서비스 계정으로 FCM HTTP v1 API 호출. 파일이 없으면 꺼짐."""

    def __init__(self, path: str):
        self.creds = None
        self.project_id = None
        if not Path(path).exists():
            print(f"FCM 꺼짐: {path} 없음", flush=True)
            return
        from google.oauth2 import service_account
        self.creds = service_account.Credentials.from_service_account_file(
            path, scopes=["https://www.googleapis.com/auth/firebase.messaging"])
        self.project_id = json.loads(Path(path).read_text())["project_id"]
        self._lock = threading.Lock()

    @property
    def enabled(self) -> bool:
        return self.creds is not None

    def _access_token(self) -> str:
        from google.auth.transport.requests import Request as GRequest
        with self._lock:
            if not self.creds.valid:
                self.creds.refresh(GRequest())
            return self.creds.token

    def send(self, token: str, title: str, body: str, data: dict, tag: str) -> bool:
        """성공하면 True, 토큰이 더 이상 유효하지 않으면 False"""
        msg = {"message": {
            "token": token,
            "notification": {"title": title, "body": body},
            "data": {k: str(v) for k, v in data.items() if v is not None},
            "android": {"priority": "high",
                        "notification": {"channel_id": "parking", "tag": tag}},
        }}
        r = httpx.post(f"https://fcm.googleapis.com/v1/projects/{self.project_id}/messages:send",
                       json=msg, headers={"Authorization": f"Bearer {self._access_token()}"},
                       timeout=10)
        if r.status_code == 404 and "UNREGISTERED" in r.text:
            return False
        if r.status_code == 400 and "registration token" in r.text.lower():
            return False
        if r.status_code >= 300:
            print(f"FCM 오류 {r.status_code}: {r.text[:300]}", flush=True)
        return True


fcm = FcmSender(FCM_CREDENTIALS)


def notify(title: str, message: str, lat: Optional[float], lon: Optional[float],
           priority: int = 4, data: Optional[dict] = None) -> None:
    data = data or {}
    if fcm.enabled:
        with _lock:
            tokens = [r["token"] for r in _db.execute("SELECT token FROM devices")]
        dead = []
        for t in tokens:
            try:
                if not fcm.send(t, title, message, data, tag=data.get("car_id", "eodi")):
                    dead.append(t)
            except Exception as e:  # 네트워크 오류 등은 다음 알림에서 다시 시도
                print(f"FCM 전송 실패: {e}", flush=True)
        if dead:
            with _lock, _db:
                _db.executemany("DELETE FROM devices WHERE token=?", [(t,) for t in dead])
    notify_ntfy(title, message, lat, lon, priority)


def notify_ntfy(title: str, message: str, lat: Optional[float], lon: Optional[float],
                priority: int) -> None:
    if not NTFY_TOPIC:
        return
    payload = {"topic": NTFY_TOPIC, "title": title, "message": message,
               "tags": ["blue_car"], "priority": priority}
    if lat is not None and lon is not None:
        url = f"https://www.google.com/maps/search/?api=1&query={lat},{lon}"
        payload["click"] = url
        payload["actions"] = [{"action": "view", "label": "지도 열기", "url": url}]
    headers = {"Authorization": f"Bearer {NTFY_TOKEN}"} if NTFY_TOKEN else {}
    try:
        httpx.post(NTFY_URL, json=payload, headers=headers, timeout=10)
    except httpx.HTTPError as e:
        print(f"ntfy 전송 실패: {e}", flush=True)


def car_name(car_id: str) -> str:
    with _lock:
        r = _db.execute("SELECT name FROM cars WHERE id=?", (car_id,)).fetchone()
    return r["name"] if r else "차"


def local_time(iso: str) -> str:
    d = datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone(LOCAL_TZ)
    return d.strftime("%H:%M")


# ---------------------------------------------------------------- API

app = FastAPI(title=APP_NAME)


def auth(authorization: str = Header(default="")) -> None:
    token = authorization.removeprefix("Bearer ").strip()
    if not secrets.compare_digest(token.encode(), FAMILY_TOKEN.encode()):
        raise HTTPException(status_code=401, detail="가족 코드가 맞지 않아요.")


class CarIn(BaseModel):
    name: str
    color: int = 0


class ParkingIn(BaseModel):
    id: str
    car_id: str
    member: str = "가족"
    date: Optional[str] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    accuracy: Optional[float] = None
    delta_meters: Optional[float] = None
    level: Optional[int] = None
    calibrated: bool = False
    pillar_text: Optional[str] = None
    ocr_level: Optional[int] = None


class ParkingPatch(BaseModel):
    level: Optional[int] = None
    calibrated: Optional[bool] = None
    pillar_text: Optional[str] = None
    ocr_level: Optional[int] = None


class GarageSample(BaseModel):
    record_id: str
    delta: float
    level: int


class GarageIn(BaseModel):
    latitude: float
    longitude: float
    name: Optional[str] = None
    samples: list[GarageSample] = []


@app.get("/api/health")
def health():
    return {"ok": True, "app": APP_NAME, "time": now_iso(), "fcm": fcm.enabled}


class DeviceIn(BaseModel):
    token: str
    member: str = "가족"
    platform: str = "android"


@app.put("/api/devices", dependencies=[Depends(auth)])
def put_device(body: DeviceIn):
    """앱이 받은 FCM 토큰을 등록(앱 시작·토큰 갱신 때마다 호출)"""
    with _lock, _db:
        _db.execute(
            """INSERT INTO devices(token, member, platform, updated_at) VALUES(?,?,?,?)
               ON CONFLICT(token) DO UPDATE SET member=excluded.member,
               platform=excluded.platform, updated_at=excluded.updated_at""",
            (body.token.strip(), body.member.strip()[:20] or "가족", body.platform[:10], now_iso()))
    return {"ok": True, "fcm": fcm.enabled}


@app.delete("/api/devices/{token}", dependencies=[Depends(auth)])
def delete_device(token: str):
    with _lock, _db:
        _db.execute("DELETE FROM devices WHERE token=?", (token,))
    return {"ok": True}


@app.post("/api/test-push", dependencies=[Depends(auth)])
def test_push(tasks: BackgroundTasks):
    tasks.add_task(notify, f"{APP_NAME} 알림 테스트", "이 알림이 보이면 연결이 잘 된 거예요.",
                   None, None, 3, {"type": "test"})
    return {"ok": True, "fcm": fcm.enabled}


@app.get("/api/state", dependencies=[Depends(auth)])
def state():
    """차 목록 + 차별 마지막 주차 + 학습된 주차장"""
    with _lock:
        cars = [car_out(r) for r in _db.execute("SELECT * FROM cars ORDER BY rowid")]
        parkings = [parking_out(r) for r in _db.execute(
            """SELECT p.* FROM parkings p
               JOIN (SELECT car_id, MAX(date) AS d FROM parkings GROUP BY car_id) last
                 ON p.car_id = last.car_id AND p.date = last.d""")]
        garages = [garage_out(r) for r in _db.execute("SELECT * FROM garages")]
    return {"cars": cars, "parkings": parkings, "garages": garages, "server_time": now_iso()}


@app.put("/api/cars/{car_id}", dependencies=[Depends(auth)])
def put_car(car_id: str, body: CarIn):
    name = body.name.strip() or "차"
    with _lock, _db:
        _db.execute(
            """INSERT INTO cars(id, name, color, updated_at) VALUES(?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET name=excluded.name, color=excluded.color,
               updated_at=excluded.updated_at""",
            (nid(car_id), name[:40], body.color, now_iso()))
    return {"ok": True}


@app.delete("/api/cars/{car_id}", dependencies=[Depends(auth)])
def delete_car(car_id: str):
    with _lock, _db:
        _db.execute("DELETE FROM cars WHERE id=?", (nid(car_id),))
    return {"ok": True}


@app.get("/api/parkings", dependencies=[Depends(auth)])
def list_parkings(car_id: Optional[str] = None, limit: int = 30):
    limit = max(1, min(limit, 200))
    with _lock:
        if car_id:
            rows = _db.execute("SELECT * FROM parkings WHERE car_id=? ORDER BY date DESC LIMIT ?",
                               (nid(car_id), limit))
        else:
            rows = _db.execute("SELECT * FROM parkings ORDER BY date DESC LIMIT ?", (limit,))
        return [parking_out(r) for r in rows]


@app.post("/api/parkings", dependencies=[Depends(auth)])
def post_parking(body: ParkingIn, tasks: BackgroundTasks):
    pid, cid, date = nid(body.id), nid(body.car_id), norm_date(body.date)
    with _lock, _db:
        existed = _db.execute("SELECT 1 FROM parkings WHERE id=?", (pid,)).fetchone()
        _db.execute(
            """INSERT INTO parkings(id, car_id, member, date, latitude, longitude, accuracy,
                   delta_meters, level, calibrated, pillar_text, ocr_level, updated_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET level=excluded.level,
                   calibrated=excluded.calibrated, pillar_text=excluded.pillar_text,
                   ocr_level=excluded.ocr_level, updated_at=excluded.updated_at""",
            (pid, cid, body.member.strip()[:20] or "가족", date, body.latitude, body.longitude,
             body.accuracy, body.delta_meters, body.level, int(body.calibrated),
             body.pillar_text, body.ocr_level, now_iso()))
    if not existed:
        msg = f"{body.member}님이 {local_time(date)}에 주차했어요"
        if body.pillar_text:
            msg += f" · {body.pillar_text}"
        tasks.add_task(notify, f"{level_name(body.level)} · {car_name(cid)}", msg,
                       body.latitude, body.longitude, 4,
                       {"type": "parked", "parking_id": pid, "car_id": cid})
    return {"ok": True, "created": not existed}


@app.patch("/api/parkings/{parking_id}", dependencies=[Depends(auth)])
def patch_parking(parking_id: str, body: ParkingPatch, tasks: BackgroundTasks):
    pid = nid(parking_id)
    changes = body.model_dump(exclude_unset=True)
    with _lock, _db:
        row = _db.execute("SELECT * FROM parkings WHERE id=?", (pid,)).fetchone()
        if not row:
            raise HTTPException(404, "주차 기록이 없어요.")
        if not changes:
            return {"ok": True}
        sets = ", ".join(f"{k}=?" for k in changes)
        values = [int(v) if isinstance(v, bool) else v for v in changes.values()]
        _db.execute(f"UPDATE parkings SET {sets}, updated_at=? WHERE id=?",
                    (*values, now_iso(), pid))
    level_changed = "level" in changes and changes["level"] != row["level"]
    pillar_changed = "pillar_text" in changes and changes["pillar_text"] != row["pillar_text"]
    if level_changed or pillar_changed:
        level = changes.get("level", row["level"])
        pillar = changes.get("pillar_text", row["pillar_text"])
        msg = f"{level_name(row['level'])} → {level_name(level)}" if level_changed else "기둥 표지 추가"
        if pillar:
            msg += f" · {pillar}"
        tasks.add_task(notify, f"수정됨: {level_name(level)} · {car_name(row['car_id'])}", msg,
                       row["latitude"], row["longitude"], 2,
                       {"type": "updated", "parking_id": pid, "car_id": row["car_id"]})
    return {"ok": True}


@app.put("/api/parkings/{parking_id}/photo", dependencies=[Depends(auth)])
async def put_photo(parking_id: str, request: Request):
    pid = nid(parking_id)
    data = await request.body()
    if not data or len(data) > MAX_PHOTO_BYTES or not data.startswith(b"\xff\xd8"):
        raise HTTPException(400, "6MB 이하 JPEG만 올릴 수 있어요.")
    with _lock:
        if not _db.execute("SELECT 1 FROM parkings WHERE id=?", (pid,)).fetchone():
            raise HTTPException(404, "주차 기록이 없어요.")
    (PHOTO_DIR / f"{pid}.jpg").write_bytes(data)
    with _lock, _db:
        _db.execute("UPDATE parkings SET has_photo=1, updated_at=? WHERE id=?", (now_iso(), pid))
    return {"ok": True}


@app.get("/api/parkings/{parking_id}/photo", dependencies=[Depends(auth)])
def get_photo(parking_id: str):
    path = PHOTO_DIR / f"{nid(parking_id)}.jpg"
    if not path.exists():
        raise HTTPException(404, "사진이 없어요.")
    return FileResponse(path, media_type="image/jpeg")


@app.put("/api/garages/{garage_id}", dependencies=[Depends(auth)])
def put_garage(garage_id: str, body: GarageIn):
    samples = [s.model_dump() | {"record_id": nid(s.record_id)} for s in body.samples][-10:]
    with _lock, _db:
        _db.execute(
            """INSERT INTO garages(id, latitude, longitude, name, samples, updated_at)
               VALUES(?,?,?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET name=excluded.name, samples=excluded.samples,
               updated_at=excluded.updated_at""",
            (nid(garage_id), body.latitude, body.longitude, body.name,
             json.dumps(samples, ensure_ascii=False), now_iso()))
    return {"ok": True}


@app.delete("/api/garages/{garage_id}", dependencies=[Depends(auth)])
def delete_garage(garage_id: str):
    with _lock, _db:
        _db.execute("DELETE FROM garages WHERE id=?", (nid(garage_id),))
    return {"ok": True}

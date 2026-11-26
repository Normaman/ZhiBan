"""Run the standalone test-set editor."""

import argparse
import secrets
import sys
from pathlib import Path

import uvicorn

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.testset_studio.server import create_app, initialize_data  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description="本地测试集管理工作台")
    parser.add_argument("--host", default="127.0.0.1", help="默认只监听本机")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=ROOT / ".runtime" / "testset-studio",
        help="独立数据目录，首次启动会从 evals 复制初始测试集",
    )
    args = parser.parse_args()
    data_dir = args.data_dir.expanduser().resolve()
    initialize_data(data_dir)
    token = secrets.token_urlsafe(32)
    app = create_app(data_dir, token)
    print(f"测试集工作台：http://{args.host}:{args.port}")
    print(f"数据目录：{data_dir}")
    if args.host not in {"127.0.0.1", "localhost", "::1"}:
        print("警告：当前监听地址可被其他设备访问，请勿在不可信网络开放")
    uvicorn.run(app, host=args.host, port=args.port, log_level="warning")


if __name__ == "__main__":
    main()

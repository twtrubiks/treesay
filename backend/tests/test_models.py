"""models.py 單元測試：日記資料落點的解析規則。"""

import pathlib

import models


class TestResolveDataDir:
    def test_env_var_wins(self, monkeypatch):
        monkeypatch.setenv("TREESAY_DATA_DIR", "/tmp/custom-treesay")
        assert models.resolve_data_dir() == pathlib.Path("/tmp/custom-treesay")

    def test_env_var_expands_user(self, monkeypatch):
        monkeypatch.setenv("TREESAY_DATA_DIR", "~/my-treesay")
        assert models.resolve_data_dir() == pathlib.Path.home() / "my-treesay"

    def test_defaults_to_home(self, monkeypatch):
        monkeypatch.delenv("TREESAY_DATA_DIR", raising=False)
        assert models.resolve_data_dir() == models.DEFAULT_DATA_DIR

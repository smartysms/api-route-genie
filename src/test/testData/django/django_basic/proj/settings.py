from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
INSTALLED_APPS = ["django.contrib.admin", "shop", "blog"]
ROOT_URLCONF = "proj.urls"

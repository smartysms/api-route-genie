from django.conf import settings
from django.conf.urls import url
from django.conf.urls.static import static
from django.contrib import admin
from django.urls import include, path, re_path

from shop import urls as shop_urls
from shop import views as shop_views

api_patterns = [
    path("ping/", shop_views.ping, name="ping"),
    path("items/<int:item_id>/", shop_views.item_api),
]

urlpatterns = [
    path("admin/", admin.site.urls),
    path("", include("shop.urls", namespace="s")),
    path("store/", include(shop_urls)),
    path("blog/", include("blog.urls")),
    path("api/", include("api"
                         ".urls_missing")),
    path("api/v1/", include(api_patterns)),
    path("inline/", include([path("a/", shop_views.ping), path("b/", shop_views.ping, name="b")])),
    re_path(r"^archive/(?P<year>[0-9]{4})/$", shop_views.archive, name="archive"),
    url(r"^legacy/(?P<slug>[\w-]+)/$", shop_views.archive),
    re_path(r"^(en|fr)/about/$", shop_views.ping),
    re_path(r"^opt/?$", shop_views.ping),
    path("docs/<path:doc_path>", shop_views.ping),
    path("files/<slug:name>/<uuid:token>/<plain>/", shop_views.ping),
    path("dyn/" + settings.DYN_SUFFIX, shop_views.ping),
]

urlpatterns += static(settings.MEDIA_URL, document_root=settings.MEDIA_ROOT)

if settings.DEBUG:
    urlpatterns += [path("__debug__/", shop_views.ping)]

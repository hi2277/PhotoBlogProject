from django.urls import include, path
from rest_framework.routers import DefaultRouter

from . import views

router = DefaultRouter()
router.register("Post", views.BlogImages, basename="post")

urlpatterns = [
    path("", views.post_list, name="post_list"),
    path("api_root/", include(router.urls)),
]
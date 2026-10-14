from django.shortcuts import render
from rest_framework import mixins, viewsets

from .models import Post
from .serializers import PostSerializer


def post_list(request):
    posts = Post.objects.select_related("author").all()
    return render(request, "blog/post_list.html", {"posts": posts})


class BlogImages(
    mixins.ListModelMixin,
    mixins.RetrieveModelMixin,
    mixins.CreateModelMixin,
    viewsets.GenericViewSet,
):
    queryset = Post.objects.select_related("author").all()
    serializer_class = PostSerializer

    def perform_create(self, serializer):
        serializer.save(author=self.request.user)
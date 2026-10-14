from rest_framework import serializers

from .models import Post


class PostSerializer(serializers.ModelSerializer):
    class Meta:
        model = Post
        fields = [
            "id",
            "author",
            "title",
            "text",
            "created_date",
            "published_date",
            "image",
        ]
        read_only_fields = ["id", "author", "created_date"]
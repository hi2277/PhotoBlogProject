import shutil
import tempfile
from io import BytesIO

from PIL import Image
from django.contrib.auth import get_user_model
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import override_settings
from django.urls import reverse
from rest_framework.authtoken.models import Token
from rest_framework.test import APITestCase

from .models import Post


class PostAPITests(APITestCase):
    def setUp(self):
        self.media_dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.media_dir)

        media_settings = override_settings(MEDIA_ROOT=self.media_dir)
        media_settings.enable()
        self.addCleanup(media_settings.disable)

        self.user = get_user_model().objects.create_user(
            username="testuser",
            password="TestPassword123!",
        )
        self.token = Token.objects.create(user=self.user)
        self.client.credentials(
            HTTP_AUTHORIZATION=f"Token {self.token.key}"
        )
        self.list_url = reverse("post-list")
        self.post = Post.objects.create(
            author=self.user,
            title="Test Photo",
            text="Test content",
            image=self.make_image(),
        )

    def make_image(self):
        buffer = BytesIO()
        Image.new("RGB", (10, 10), color="blue").save(
            buffer, format="PNG"
        )
        return SimpleUploadedFile(
            "test.png",
            buffer.getvalue(),
            content_type="image/png",
        )

    def test_list_posts(self):
        response = self.client.get(self.list_url)

        self.assertEqual(response.status_code, 200)
        self.assertIsInstance(response.data, list)
        self.assertEqual(len(response.data), 1)
        self.assertEqual(response.data[0]["title"], "Test Photo")
        self.assertTrue(
            response.data[0]["image"].startswith("http://testserver/media/")
        )

    def test_get_single_post(self):
        url = reverse("post-detail", args=[self.post.pk])
        response = self.client.get(url)

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data["id"], self.post.pk)

    def test_upload_photo(self):
        response = self.client.post(
            self.list_url,
            {
                "title": "Uploaded Photo",
                "text": "Uploaded through API",
                "image": self.make_image(),
            },
            format="multipart",
        )

        self.assertEqual(response.status_code, 201)
        self.assertEqual(Post.objects.count(), 2)

        uploaded = Post.objects.get(pk=response.data["id"])
        self.assertEqual(uploaded.author, self.user)
        self.assertEqual(uploaded.title, "Uploaded Photo")
        self.assertTrue(
            uploaded.image.storage.exists(uploaded.image.name)
        )

    def test_reject_unauthenticated_read(self):
        self.client.credentials()
        response = self.client.get(self.list_url)

        self.assertEqual(response.status_code, 401)

    def test_reject_unauthenticated_upload(self):
        self.client.credentials()
        response = self.client.post(
            self.list_url,
            {
                "title": "Unauthorized",
                "text": "Should not be saved",
                "image": self.make_image(),
            },
            format="multipart",
        )

        self.assertEqual(response.status_code, 401)
        self.assertEqual(Post.objects.count(), 1)

    def test_reject_missing_title(self):
        response = self.client.post(
            self.list_url,
            {
                "text": "Missing title",
                "image": self.make_image(),
            },
            format="multipart",
        )

        self.assertEqual(response.status_code, 400)
        self.assertIn("title", response.data)
        self.assertEqual(Post.objects.count(), 1)

    def test_reject_invalid_image(self):
        invalid_image = SimpleUploadedFile(
            "invalid.png",
            b"This is not an image",
            content_type="image/png",
        )
        response = self.client.post(
            self.list_url,
            {
                "title": "Invalid Image",
                "text": "Should not be saved",
                "image": invalid_image,
            },
            format="multipart",
        )

        self.assertEqual(response.status_code, 400)
        self.assertIn("image", response.data)
        self.assertEqual(Post.objects.count(), 1)

    def test_missing_post_returns_404(self):
        url = reverse("post-detail", args=[self.post.pk + 1000])
        response = self.client.get(url)

        self.assertEqual(response.status_code, 404)
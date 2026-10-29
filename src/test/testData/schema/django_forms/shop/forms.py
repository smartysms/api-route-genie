from django import forms

from .models import Product


class ContactForm(forms.Form):
    email = forms.EmailField()
    message = forms.CharField(max_length=500, required=False)
    age = forms.IntegerField(min_value=18)
    topics = forms.MultipleChoiceField(choices=[("a", "Alpha"), ("b", "Beta")])


class UploadForm(forms.Form):
    title = forms.CharField()
    attachment = forms.FileField()


class ProductForm(forms.ModelForm):
    class Meta:
        model = Product
        fields = ["name", "description", "price", "in_stock", "category"]

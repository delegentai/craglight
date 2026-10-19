"""Google verification and image normalization; never persist incoming tokens/EXIF."""
import hashlib
import io
import time
import warnings
from urllib.parse import urlparse
import cachecontrol
import requests
from google.auth.transport.requests import Request
from google.oauth2 import id_token
from PIL import Image, ImageOps, UnidentifiedImageError

MAX_IMAGE_BYTES=8*1024*1024
MAX_PIXELS=12_000_000
Image.MAX_IMAGE_PIXELS=MAX_PIXELS

class GoogleVerifier:
    def __init__(self, client_id, transport=None):
        self.client_id=client_id
        self.transport=transport or Request(session=cachecontrol.CacheControl(requests.Session()))

    def __call__(self, token):
        # This is library signature verification, not decoding an unverified JWT.
        claims=id_token.verify_oauth2_token(token,self.transport,self.client_id,clock_skew_in_seconds=30)
        if claims.get('aud') != self.client_id or claims.get('iss') not in ('accounts.google.com','https://accounts.google.com'):
            raise ValueError('Invalid token audience/issuer')
        if not isinstance(claims.get('sub'),str) or not 1<=len(claims['sub'])<=255:
            raise ValueError('Invalid subject')
        if claims.get('iat',0)>time.time()+30 or claims.get('exp',0)<=time.time():
            raise ValueError('Expired/future token')
        return claims

def normalize_image(data: bytes):
    if not data or len(data)>MAX_IMAGE_BYTES: raise ValueError('Image exceeds 8 MiB')
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error',Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data),formats=['JPEG','PNG']) as image:
                if image.width*image.height>MAX_PIXELS or getattr(image,'n_frames',1)!=1:
                    raise ValueError('Image exceeds 12 megapixels or is animated')
                image.verify()
            with Image.open(io.BytesIO(data),formats=['JPEG','PNG']) as image:
                image.load()
                upright=ImageOps.exif_transpose(image)
                upright.thumbnail((2400,2400),Image.Resampling.LANCZOS)
                # New RGB pixel buffer deliberately carries no EXIF, text, ICC, XMP or original filename.
                clean=Image.new('RGB',upright.size,(255,255,255))
                if 'A' in upright.getbands(): clean.paste(upright.convert('RGB'),mask=upright.getchannel('A'))
                else: clean.paste(upright.convert('RGB'))
                out=io.BytesIO();clean.save(out,'JPEG',quality=88,optimize=True)
                result=out.getvalue()
                return result,clean.width,clean.height,hashlib.sha256(result).hexdigest()
    except (OSError,UnidentifiedImageError,Image.DecompressionBombError,Image.DecompressionBombWarning) as exc:
        raise ValueError('Invalid or unsafe JPEG/PNG') from exc

def google_photo(url):
    parsed=urlparse(url or '')
    # Fixed trusted host, no arbitrary URL fetch/redirects and no user-supplied override.
    if parsed.scheme!='https' or parsed.hostname not in ('lh3.googleusercontent.com','lh4.googleusercontent.com','lh5.googleusercontent.com','lh6.googleusercontent.com') or parsed.username or parsed.port:
        raise ValueError('Unsupported Google picture host')
    with requests.get(url,stream=True,timeout=(3,5),allow_redirects=False) as response:
        if response.status_code!=200: raise ValueError('Google picture unavailable')
        data=bytearray()
        for chunk in response.iter_content(65536):
            data.extend(chunk)
            if len(data)>MAX_IMAGE_BYTES: raise ValueError('Google picture too large')
    return normalize_image(bytes(data))

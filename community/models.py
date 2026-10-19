"""Strict, bounded API schemas. A private draft may be incomplete."""
from datetime import date
from typing import Literal
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field, field_validator

class Model(BaseModel):
    model_config = ConfigDict(extra='forbid', str_strip_whitespace=True, allow_inf_nan=False)

class Login(Model):
    idToken: str = Field(min_length=20, max_length=12000)
    challengeId: UUID
    publicAlias: str | None = Field(default=None, min_length=2, max_length=40)
    useGooglePhoto: bool = False
    termsAccepted: bool
    licenceAccepted: bool

class ProfileEdit(Model):
    publicAlias: str | None = Field(default=None, min_length=2, max_length=40)
    removePhoto: bool = False

class Crag(Model):
    name: str = Field(default='', max_length=150)
    existingId: str | None = Field(default=None, max_length=160)
    lat: float | None = Field(default=None, ge=49, le=61)
    lon: float | None = Field(default=None, ge=-9, le=2.2)

class Parking(Model):
    lat: float | None = Field(default=None, ge=49, le=61)
    lon: float | None = Field(default=None, ge=-9, le=2.2)
    description: str = Field(default='', max_length=2000)

class Point(Model):
    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)

class Photo(Model):
    id: UUID
    width: int = Field(ge=1, le=2400)
    height: int = Field(ge=1, le=2400)
    sha256: str = Field(pattern=r'^[a-f0-9]{64}$')
    originalWork: bool

class Route(Model):
    id: UUID
    name: str = Field(default='', max_length=150)
    gradeSystem: Literal['ukTrad','ukTech','french','font','v'] = 'ukTrad'
    grade: str = Field(default='', max_length=24)
    style: Literal['trad','sport','boulder','mixed'] = 'trad'
    description: str = Field(default='', max_length=4000)
    photoId: UUID | None = None
    lines: list[list[Point]] = Field(default_factory=list, max_length=12)

    @field_validator('lines')
    @classmethod
    def bounded_lines(cls, lines):
        if any(len(line)<2 or len(line)>200 for line in lines):
            raise ValueError('Each line needs 2–200 normalized points')
        return lines

class Draft(Model):
    id: UUID
    kind: Literal['guide','gallery'] = 'guide'
    basePublicationId: UUID | None = None
    crag: Crag = Field(default_factory=Crag)
    parking: Parking | None = None
    parkingUnknownReason: str = Field(default='', max_length=1000)
    approach: str = Field(default='', max_length=6000)
    descent: str = Field(default='', max_length=6000)
    access: str = Field(default='', max_length=4000)
    accessCheckedAt: date | None = None
    photos: list[Photo] = Field(default_factory=list,max_length=12)
    routes: list[Route] = Field(default_factory=list,max_length=100)
    contributorAlias: str = Field(default='',max_length=40)
    licence: Literal['CC-BY-SA-4.0'] = 'CC-BY-SA-4.0'
    consent: bool = False
    caption: str = Field(default='',max_length=2000)

    @field_validator('photos','routes')
    @classmethod
    def unique_ids(cls, items):
        if len({str(x.id) for x in items}) != len(items): raise ValueError('Duplicate IDs')
        return items

class Report(Model):
    targetType: Literal['crag','photo','profile','gallery']
    targetId: str = Field(min_length=1,max_length=160)
    reason: Literal['safety','access','copyright','privacy','harassment','illegal','spam','other']
    details: str = Field(default='',max_length=3000)

class Review(Model):
    decision: Literal['approve','reject','changes_requested']
    reason: str = Field(min_length=10,max_length=2000)
    evidenceUrl: str | None = Field(default=None,max_length=1000)
    rightsChecked: bool = False
    safetyChecked: bool = False
    accessChecked: bool = False
    completenessChecked: bool = False

class ReportReview(Model):
    action: Literal['dismiss','hide']
    reason: str = Field(min_length=10,max_length=2000)

class ProfileReview(Model):
    decision: Literal['approve','reject']
    approveAlias: bool = False
    approvePhoto: bool = False
    reason: str = Field(min_length=10,max_length=2000)

class DeleteAccount(Model):
    confirmation: Literal['DELETE']

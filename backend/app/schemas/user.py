from datetime import date

from pydantic import BaseModel, ConfigDict, EmailStr, field_validator

ALLOWED_SEX_VALUES = {"male", "female", "other", "prefer_not_to_say"}


def normalize_cpf(value: str) -> str:
    return "".join(
        char
        for char in value
        if char.isdigit()
    )


def cpf_is_valid(value: str) -> bool:
    cpf = normalize_cpf(value)

    if len(cpf) != 11:
        return False

    if len(set(cpf)) == 1:
        return False

    def digit(length: int) -> int:
        total = 0
        weight = length + 1

        for index in range(length):
            total += int(cpf[index]) * weight
            weight -= 1

        result = (total * 10) % 11
        return 0 if result == 10 else result

    return (
        int(cpf[9]) == digit(9)
        and int(cpf[10]) == digit(10)
    )


def validate_full_name(value: str) -> str:
    value = " ".join(value.strip().split())

    if len(value.split()) < 2:
        raise ValueError(
            "Informe seu nome completo"
        )

    return value


def validate_cpf_value(value: str) -> str:
    cpf = normalize_cpf(value)

    if not cpf_is_valid(cpf):
        raise ValueError("CPF inválido")

    return cpf


class UserCreate(BaseModel):
    email: EmailStr
    name: str
    cpf: str
    password: str
    birth_date: date
    sex: str
    legal_accepted: bool
    terms_version: str
    privacy_version: str

    @field_validator("name")
    @classmethod
    def validate_name(cls, value: str) -> str:
        return validate_full_name(value)

    @field_validator("cpf")
    @classmethod
    def validate_cpf(cls, value: str) -> str:
        return validate_cpf_value(value)

    @field_validator("password")
    @classmethod
    def validate_password(cls, value: str) -> str:
        if len(value) < 6:
            raise ValueError("A senha deve ter pelo menos 6 caracteres")
        return value

    @field_validator("birth_date")
    @classmethod
    def validate_birth_date(cls, value: date) -> date:
        if value >= date.today():
            raise ValueError("Informe uma data de nascimento válida")
        return value

    @field_validator("sex")
    @classmethod
    def validate_sex(cls, value: str) -> str:
        if value not in ALLOWED_SEX_VALUES:
            raise ValueError("Sexo inválido")
        return value


class UserResponse(BaseModel):
    id: int
    email: EmailStr
    name: str | None = None
    cpf: str | None = None
    birth_date: date | None = None
    sex: str | None = None
    profile_photo: str | None = None
    has_google_profile_photo: bool = False

    model_config = ConfigDict(from_attributes=True)


class PersonalProfileUpdate(BaseModel):
    name: str
    cpf: str
    birth_date: date | None = None
    sex: str | None = None

    @field_validator("name")
    @classmethod
    def validate_name(cls, value: str) -> str:
        return validate_full_name(value)

    @field_validator("cpf")
    @classmethod
    def validate_cpf(cls, value: str) -> str:
        return validate_cpf_value(value)

    @field_validator("birth_date")
    @classmethod
    def validate_birth_date(cls, value: date | None) -> date | None:
        if value is not None and value >= date.today():
            raise ValueError("Informe uma data de nascimento válida")
        return value

    @field_validator("sex")
    @classmethod
    def validate_sex(cls, value: str | None) -> str | None:
        if value is not None and value not in ALLOWED_SEX_VALUES:
            raise ValueError("Sexo inválido")
        return value


class ProfilePhotoUpdate(BaseModel):
    profile_photo: str | None = None

    @field_validator("profile_photo")
    @classmethod
    def validate_profile_photo(cls, value: str | None) -> str | None:
        if value is not None and len(value) > 1_500_000:
            raise ValueError("A foto de perfil é muito grande")
        return value


class OpenFinanceProfileUpdate(BaseModel):
    name: str
    cpf: str

    @field_validator("name")
    @classmethod
    def validate_name(cls, value: str) -> str:
        return validate_full_name(value)

    @field_validator("cpf")
    @classmethod
    def validate_cpf(cls, value: str) -> str:
        return validate_cpf_value(value)

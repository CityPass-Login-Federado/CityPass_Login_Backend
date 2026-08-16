# CityPass_Login_Backend
- no persiste refresh token
- no incluye claims name/email (requiere un UserDetailsContextMapper custom para leer esos atributos de LDAP)
- no publica el evento usuario.autenticado al bus.
- /auth/registro
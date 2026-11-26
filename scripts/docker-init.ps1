$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$envPath = Join-Path $root "infra/.env"

if (Test-Path $envPath) {
    Write-Output "infra/.env already exists; leaving existing credentials unchanged."
    exit 0
}

$pythonCode = 'import base64, os, secrets; values={"DATABASE_PASSWORD":secrets.token_urlsafe(32),"REVIEWER_USER":"reviewer","REVIEWER_PASSWORD":secrets.token_urlsafe(24),"INTERNAL_TOKEN":secrets.token_urlsafe(36),"METRICS_TOKEN":secrets.token_urlsafe(36),"REGISTRATION_INVITE":secrets.token_urlsafe(32),"REDIS_PASSWORD":secrets.token_urlsafe(32),"MODEL_ENCRYPTION_KEY":base64.b64encode(secrets.token_bytes(32)).decode(),"LLM_BASE_URL":"","LLM_MODEL":"","LLM_API_KEY":""}; path="/out/.env"; fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600); stream=os.fdopen(fd,"w"); stream.write("\n".join(f"{k}={v}" for k,v in values.items())+"\n"); stream.close()'
$mount = "type=bind,source=$root/infra,target=/out"
docker run --rm --mount $mount python:3.12-alpine python -c $pythonCode
if ($LASTEXITCODE -ne 0) {
    throw "Docker could not create infra/.env. Check Docker Desktop is running."
}
Write-Output "Created infra/.env with fresh local credentials. Read it locally for the initial admin login."

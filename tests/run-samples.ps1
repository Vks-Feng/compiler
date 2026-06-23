$ErrorActionPreference = "Stop"

mvn -q -DskipTests package

$samples = @(
    @{
        Name = "basic"
        Source = @'
int main() { return 42; }
'@
    },
    @{
        Name = "control-flow"
        Source = @'
const int C = 7;
int g = 3;
int add(int a, int b) { return a + b + C; }
int main() {
  int x = 0;
  int i = 0;
  while (i < 5) {
    x = x + i;
    i = i + 1;
  }
  if (x && add(g, 2) == 12) return x;
  return 1;
}
'@
    },
    @{
        Name = "nested-call"
        Source = @'
int id(int x) { return x; }
int sum10(int a,int b,int c,int d,int e,int f,int g,int h,int i,int j) {
  return a+b+c+d+e+f+g+h+i+j;
}
int main() { return sum10(id(1), id(2 + id(3)), 4,5,6,7,8,9,10,11); }
'@
    },
    @{
        Name = "int-min"
        Source = @'
int main() { return -2147483648 < 0; }
'@
    }
)

foreach ($sample in $samples) {
    $asmLines = $sample.Source | java -jar target/toyc-compiler-1.0.0.jar
    $asm = [string]::Join("`n", $asmLines)
    if ($LASTEXITCODE -ne 0) {
        throw "sample failed: $($sample.Name)"
    }
    if ($asm -notmatch "\.text" -or $asm -notmatch "\.globl main" -or $asm -notmatch "`nmain:") {
        throw "assembly missing required main section: $($sample.Name)"
    }
    Write-Host "ok $($sample.Name)"
}

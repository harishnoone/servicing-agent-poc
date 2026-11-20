# Scenario 7: poison pill. Bad CDC record retries 1s/2s/4s, lands on the DLT; healthy events keep flowing.
. "$PSScriptRoot\_common.ps1"
Require-Stack
Title "7. Poison pill -> retries -> dead-letter topic"

function DltDepth { (@((Api GET "/api/admin/kafka/dlt").'db.public.customers.DLT'.pending)).Count }
$depthBefore = DltDepth
Say "Publishing an unparseable record to db.public.customers..."
$p = Api POST "/api/demo/poison-pill" @{ table = "customers" }
Write-Host "  -> $($p.message)" -ForegroundColor DarkGray
Say "Waiting for the 1s + 2s + 4s retries..."
$landed = WaitFor { (DltDepth) -gt $depthBefore } 20
Check "db.public.customers.DLT pending $depthBefore -> $(DltDepth)" $landed
Say "Healthy events still flow: inserting a charge for Jordan (cust_789)..."
$m = Api POST "/api/demo/mutate" @{ customerId = "cust_789"; chargeType = "OVERAGE"; amount = 5.00; description = "After poison pill" }
Check "healthy event reached Redis" (WaitFor { (Profile "cust_789").recentCharges | Where-Object { $_.invoiceId -eq $m.invoiceId } } 20)
Finish

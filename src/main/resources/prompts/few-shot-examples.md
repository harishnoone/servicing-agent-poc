## Worked examples (tool order and tone)

Example 1: late fee waiver request
1. getCustomerState for the customer id in the request.
2. searchPoliciesAndCases("late fee waiver policy") and note the returned documentId.
3. evaluateWaiverRules(customerId, amount) and report its reasonCode and explanation.
4. escalateWithSummary only if it is needed. Reply briefly, cite the documentId returned in step 2, and say
   "approved" only when the reasonCode was APPROVED.

Example 2: error code troubleshooting
1. getCustomerState, then searchPoliciesAndCases with the exact error code.
2. Follow the result's guidance field. If a result is marked superseded, give the canonical fix and say the
   older guidance was superseded.
3. If a ticket is past its SLA, call escalateWithSummary and tell the customer a specialist will follow up
   with full context.

Example 3: unclear or low-confidence question
1. searchPoliciesAndCases. If it returns lowConfidence=true, call escalateWithSummary instead of guessing.
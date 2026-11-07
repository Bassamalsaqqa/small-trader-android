# Final independent sale review and lead corrections

A separate read-only Codex reviewer examined catalog/review/payment/receipt integration and QA fixes. It found two concrete retained-workflow defects before commit:

1. Compact empty basket returned before the Options link, leaving saved drafts unreachable without adding an unrelated product. Lead added a concise Saved drafts action to the empty dock, wired to the existing empty review/draft surface. Empty review now scrolls its draft list on small screens.
2. App-wide saveable screen state could restore Credit payment mode after a completed sale. Lead added a UI-only saleSessionId, advanced when clearing, completing, holding or replacing a sale draft, and scopes checkout saveable state to that generation. Ordinary same-sale Back retains its state; new sales default to Cash. No repository/posting calculation changed.

MerchantSaleLifecycleTest exercises the actual SmallTraderApp shell for both scenarios. Execution results and final APK attribution belong in VALIDATION.md and ARTIFACT.json; the earlier 70-pass APK predates these two fixes and must not be delivered as final.

Reviewer found no additional posting, stock, credit-guard or historical-receipt regression. Exact cash fills input only and final posting callbacks remain guarded. Printing remains explicitly simulated. Pixel layout, large fonts, IME, RTL, touch and performance are still unverified. This review is not UX approval.

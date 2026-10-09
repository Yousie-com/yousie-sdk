# Play Billing is optional for the SDK: an app without it must still build
# with R8 (the SDK only touches these classes after checking they exist).
-dontwarn com.android.billingclient.api.**

package com.example.merchandisecontrolsplitview.data

/** An edited field changed concurrently, or the edited row no longer exists. No write was applied. */
class ProductEditConflictException : IllegalStateException("Product changed while the editor was open")

package com.example.merchandisecontrolsplitview.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/** Survives business-store opening without creating any repository, DAO or auth reader. */
internal class RamExcelImportViewModel : ViewModel() {
    val draft = ExcelImportDraftState(viewModelScope)
}

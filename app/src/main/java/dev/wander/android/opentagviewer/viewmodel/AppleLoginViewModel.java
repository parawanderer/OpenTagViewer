package dev.wander.android.opentagviewer.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class AppleLoginViewModel extends ViewModel {
    private final MutableLiveData<LoginActivityState> loginActivityState = new MutableLiveData<>(new LoginActivityState());

    public LiveData<LoginActivityState> getUiState() {
        return this.loginActivityState;
    }

    public void resetUiState() {
        this.loginActivityState.postValue(new LoginActivityState());
    }
}

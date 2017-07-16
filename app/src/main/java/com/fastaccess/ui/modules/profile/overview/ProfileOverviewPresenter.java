package com.fastaccess.ui.modules.profile.overview;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.view.View;

import com.fastaccess.data.dao.CreateGistModel;
import com.fastaccess.data.dao.FilesListModel;
import com.fastaccess.data.dao.model.Login;
import com.fastaccess.data.dao.model.User;
import com.fastaccess.helper.BundleConstant;
import com.fastaccess.helper.InputHelper;
import com.fastaccess.helper.PrefGetter;
import com.fastaccess.helper.RxHelper;
import com.fastaccess.provider.rest.ImgurProvider;
import com.fastaccess.provider.rest.RestProvider;
import com.fastaccess.ui.base.mvp.presenter.BasePresenter;
import com.fastaccess.ui.widgets.contributions.ContributionsDay;
import com.fastaccess.ui.widgets.contributions.ContributionsProvider;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import rx.Observable;

/**
 * Created by Kosh on 03 Dec 2016, 9:16 AM
 */

class ProfileOverviewPresenter extends BasePresenter<ProfileOverviewMvp.View> implements ProfileOverviewMvp.Presenter {
    @icepick.State boolean isSuccessResponse;
    @icepick.State boolean isFollowing;
    @icepick.State String login;
    @icepick.State ArrayList<User> userOrgs = new ArrayList<>();
    private ArrayList<ContributionsDay> contributions = new ArrayList<>();
    private static final String URL = "https://github.com/users/%s/contributions";
    private DatabaseReference bannerRef;

    @Override public void onCheckFollowStatus(@NonNull String login) {
        if (!TextUtils.equals(login, Login.getUser().getLogin()))
            makeRestCall(RestProvider.getUserService().getFollowStatus(login),
                    booleanResponse -> {
                        isSuccessResponse = true;
                        isFollowing = booleanResponse.code() == 204;
                        sendToView(ProfileOverviewMvp.View::invalidateFollowBtn);
                    });
    }

    @Override public boolean isSuccessResponse() {
        return isSuccessResponse;
    }

    @Override public boolean isFollowing() {
        return isFollowing;
    }

    @Override public void onFollowButtonClicked(@NonNull String login) {
        manageSubscription(RxHelper.getObserver(!isFollowing ? RestProvider.getUserService().followUser(login)
                                                             : RestProvider.getUserService().unfollowUser(login))
                .subscribe(booleanResponse -> {
                    if (booleanResponse.code() == 204) {
                        isFollowing = !isFollowing;
                        sendToView(ProfileOverviewMvp.View::invalidateFollowBtn);
                    }
                }, this::onError));
    }

    @Override public void onError(@NonNull Throwable throwable) {
        int statusCode = RestProvider.getErrorCode(throwable);
        if (statusCode == 404) {
            sendToView(ProfileOverviewMvp.View::onUserNotFound);
            return;
        }
        if (!InputHelper.isEmpty(login)) {
            onWorkOffline(login);
        }
        sendToView(ProfileOverviewMvp.View::invalidateFollowBtn);
        super.onError(throwable);
    }

    @Override public void onFragmentCreated(@Nullable Bundle bundle) {
        if (bundle == null || bundle.getString(BundleConstant.EXTRA) == null) {
            throw new NullPointerException("Either bundle or User is null");
        }
        login = bundle.getString(BundleConstant.EXTRA);
        if (login != null) {
            loadOrgs();
            loadContributions();
            makeRestCall(RestProvider.getUserService().getUser(login), userModel -> {
                onSendUserToView(userModel);
                if (userModel != null) {
                    userModel.save(userModel);
                    if (userModel.getType() != null && userModel.getType().equalsIgnoreCase("user")) {
                        onCheckFollowStatus(login);
                    }
                }
            });

            FirebaseDatabase database = FirebaseDatabase.getInstance();
            bannerRef = database.getReference("banners");

            bannerRef.child(getLogin()).push();
            bannerRef.child(getLogin()).addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    sendToView(view -> view.onImagePosted(dataSnapshot.getValue(String.class)));
                }

                @Override
                public void onCancelled(DatabaseError error) {
                }
            });

        }
    }

    @Override public void onWorkOffline(@NonNull String login) {
        User userModel = User.getUser(login);
        if (userModel == null) {
            return;
        }
        onSendUserToView(userModel);
    }

    @Override public void onSendUserToView(@Nullable User userModel) {
        sendToView(view -> view.onInitViews(userModel));
    }

    @NonNull @Override public ArrayList<User> getOrgs() {
        return userOrgs;
    }

    @NonNull @Override public ArrayList<ContributionsDay> getContributions() {
        return contributions;
    }

    @NonNull @Override public String getLogin() {
        return login;
    }

    @Override public void onPostImage(@NonNull String path) {
        RequestBody image = RequestBody.create(MediaType.parse("image/*"), new File(path));
        RxHelper.getObserver(ImgurProvider.getImgurService().postImage("", image)).subscribe(imgurReponseModel -> {
            if (imgurReponseModel!=null&&imgurReponseModel.getData()!=null
                    &&!imgurReponseModel.getData().getLink().isEmpty()) {
                bannerRef.setValue(getLogin(), imgurReponseModel.getData().getLink());
                sendToView(view -> view.onImagePosted(imgurReponseModel.getData().getLink()));
            }
        });
    }

    @NonNull private Observable<String> getHeaderGist() {
        return RxHelper.getObserver(RestProvider.getGistService(true).getGistFile(ProfileOverviewMvp.HEADER_FST_URL));
    }

    private void loadContributions() {
        String url = String.format(URL, login);
        manageSubscription(RxHelper.getObserver(RestProvider.getContribution().getContributions(url))
                .flatMap(s -> Observable.just(new ContributionsProvider().getContributions(s)))
                .subscribe(lists -> {
                    contributions.clear();
                    contributions.addAll(lists);
                    sendToView(view -> view.onInitContributions(contributions));
                }, Throwable::printStackTrace));
    }

    private void loadOrgs() {
        boolean isMe = login.equalsIgnoreCase(Login.getUser() != null ? Login.getUser().getLogin() : "");
        manageSubscription(RxHelper.getObserver(isMe ? RestProvider.getOrgService().getMyOrganizations()
                                                     : RestProvider.getOrgService().getMyOrganizations(login))
                .subscribe(response -> {
                    if (response != null && response.getItems() != null) {
                        userOrgs.addAll(response.getItems());
                    }
                    sendToView(view -> view.onInitOrgs(userOrgs));
                }, Throwable::printStackTrace));
    }

}
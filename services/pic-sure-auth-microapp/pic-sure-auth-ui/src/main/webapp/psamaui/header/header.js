define(["backbone","handlebars", "text!header/header.hbs", "common/session", "picSure/userFunctions","picSure/applicationFunctions", "text!options/modal.hbs","text!header/userProfile.hbs", "util/notification"],
		function(BB, HBS, template, session, userFunctions, applicationFunctions,modalTemplate, userProfileTemplate, notification){
	var headerView = BB.View.extend({
        initialize: function () {
            HBS.registerHelper('not_contains', function (array, object, opts) {
                var found = _.find(array, function (element) {
                    return (element === object);
                });
                if (found)
                    return opts.inverse(this);
                else
                    return opts.fn(this);
            });
            HBS.registerHelper('not_empty', function (array, opts) {
                if (array.length>0)
                    return opts.fn(this);
                else
                    return opts.inverse(this);

            });
            this.template = HBS.compile(template);
            this.applications = [];
            this.modalTemplate = HBS.compile(modalTemplate);
            this.userProfileTemplate = HBS.compile(userProfileTemplate);
        },
        events: {
            "click #logout-btn": "gotoLogin",
            "click #user-profile-btn": "userProfile"
        },
        gotoLogin: function (event) {
            this.logout();
            window.location = "/psamaui/login" + window.location.search;
        },
        userProfile: function (event) {
            userFunctions.meWithToken(this, function(user){
                $("#modal-window").html(this.modalTemplate({title: "User Profile"}));
                $("#modalDialog").show();
                $(".modal-body").html(this.userProfileTemplate({user:user}));
                $("#user-token-copy-button").click(this.copyToken);
                $("#user-token-refresh-button").click(this.refreshToken);
                $('#user-token-reveal-button').click(this.revealToken);
                $('.close').click(this.closeDialog);
            }.bind(this));
        },
        copyToken: function(){
            var originValue = document.getElementById("user_token_textarea").textContent;

            var sel = getSelection();
            var range = document.createRange();

            // this if for supporting chrome, since chrome will look for value instead of textContent
            // document.getElementById("user_token_textarea").value = document.getElementById("user_token_textarea").textContent;
            document.getElementById("user_token_textarea").value
                = document.getElementById("user_token_textarea").textContent
                = document.getElementById("user_token_textarea").attributes.token.value;
            range.selectNode(document.getElementById("user_token_textarea"));
            sel.removeAllRanges();
            sel.addRange(range);
            document.execCommand("copy");

            $("#user-token-copy-button").html("COPIED");

            document.getElementById("user_token_textarea").textContent
                = document.getElementById("user_token_textarea").value
                = originValue;
        },
        refreshToken: function(){
            notification.showConfirmationDialog(function () {
                userFunctions.refreshUserLongTermToken(this, function(result){
                    if ($('#user-token-reveal-button').html() == "HIDE"){
                        $("#user_token_textarea").html(result.userLongTermToken);
                    }

                    document.getElementById("user_token_textarea").attributes.token.value = result.userLongTermToken;

                    $("#user-token-copy-button").html("COPY");
                }.bind(this));
            }.bind(this), 'center', 'Refresh will inactivate the old token!! Do you want to continue?');
        },
        revealToken: function(event){
            var type = $('#user-token-reveal-button').html();
            if (type == "REVEAL"){
                var token = $('#user_token_textarea')[0].attributes.token.value;
                $("#user_token_textarea").html(token);
                $("#user-token-reveal-button").html("HIDE");
            } else {
                $("#user_token_textarea").html("**************************************************************************************************************************************************************************************************************************************************************************************");
                $("#user-token-reveal-button").html("REVEAL");
            }
        },
        closeDialog: function () {
            $("#modalDialog").hide();
        },
        logout: function (event) {
            sessionStorage.clear();
            localStorage.clear();
        },
        render: function () {
            if (window.location.pathname !== "/psamaui/tos") {
                if (window.location.pathname == "/psamaui/userProfile"){
                    applicationFunctions.fetchApplications(this, function(applications){
                        this.$el.html(this.template({
                            privileges: [],
                            applications: applications
                                .filter(function (app) {
                                    return app.url;
                                })
                                .sort(function(a, b){
                                    if(a.name < b.name) { return -1; }
                                    if(a.name > b.name) { return 1; }
                                    return 0;
                                })
                        }));
                    }.bind(this));
                }else {
                    userFunctions.me(this, function (user) {
                        applicationFunctions.fetchApplications(this, function(applications){
                            this.applications = applications;
                            this.$el.html(this.template({
                                privileges: user.privileges,
                                applications: this.applications
                                    .filter(function (app) {
                                        return app.url;
                                    })
                                    .sort(function(a, b){
                                        if(a.name < b.name) { return -1; }
                                        if(a.name > b.name) { return 1; }
                                        return 0;
                                    })
                            }));
                        }.bind(this))

                    }.bind(this));
                }
            }
        }
    });

	return {
		View : new headerView({})
	};
});

define(["backbone","handlebars", "text!header/header.hbs", "common/session", "picSure/userFunctions", "picSure/applicationFunctions"],
		function(BB, HBS, template, session, userFunctions, applicationFunctions){
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
            this.template = HBS.compile(template);
            this.applications = [];
        },
        events: {
            "click #logout-btn": "gotoLogin"
        },
        gotoLogin: function (event) {
            this.logout();
            window.location = "/psamaui/login" + window.location.search;
        },
        logout: function (event) {
            sessionStorage.clear();
            localStorage.clear();
        },
        render: function () {
            if (window.location.pathname !== "/psamaui/tos") {
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
    });

	return {
		View : new headerView({})
	};
});

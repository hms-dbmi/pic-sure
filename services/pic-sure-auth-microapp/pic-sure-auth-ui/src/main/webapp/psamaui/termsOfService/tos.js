define(["backbone","handlebars", "text!termsOfService/tos.hbs", "picSure/picsureFunctions", 'common/session'],
    function(BB, HBS, template, picsureFunctions, session){
        var tosModel = BB.Model.extend({
        });

        var tosView = BB.View.extend({
            template : HBS.compile(template),
            events : {
                "click .accept-tos-button":   "acceptTOS",
            },
            acceptTOS: function () {
                picsureFunctions.acceptTOS(function(){
                    this.toggleNavigationButtons(false);
                    session.setAcceptedTOS();
                    if (sessionStorage.redirection_url) {
                        window.location = sessionStorage.redirection_url;
                    }
                    else {
                        history.pushState({}, "", "psamaui/userManagement");
                    }
                }.bind(this))
            },
            toggleNavigationButtons: function(disable) {
                if (disable) {
                    $("#userMgmt-header").removeAttr('href');
                    $("#cnxn-header").removeAttr('href');
                    $("#userMgmt-header").attr('title', 'You must accept the terms of service before using other pages.');
                    $("#cnxn-header").attr('title', 'You must accept the terms of service before using other pages.');
                } else {
                    $("#userMgmt-header").attr("href","/userManagement");
                    $("#cnxn-header").attr("href","/connectionManagement");
                    $("#userMgmt-header").removeAttr('title');
                    $("#cnxn-header").removeAttr('title');
                }
            },
            render : function(){
                picsureFunctions.getLatestTOS(function (content) {
                    this.model.set("content", content);
                    this.$el.html(this.template({content: content}));
                    this.toggleNavigationButtons(true);
                }.bind(this));
            }
        });

        return {
            View : tosView,
            Model: tosModel
        };
});
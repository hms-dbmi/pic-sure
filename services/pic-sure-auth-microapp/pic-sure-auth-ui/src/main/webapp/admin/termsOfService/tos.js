define(["backbone","handlebars", "text!termsOfService/tos.hbs", "picSure/picsureFunctions"],
    function(BB, HBS, template, picsureFunctions){
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
                    if (sessionStorage.redirection_url) {
                        window.location = sessionStorage.redirection_url;
                    }
                    else {
                        history.pushState({}, "", "userManagement");
                    }
                }.bind(this))
            },
            toggleNavigationButtons: function(disable) {
                console.log("toggling buttons");
                if (disable) {
                    $("#userMgmt-header").removeAttr('href');
                    $("#cnxn-header").removeAttr('href');
                } else {
                    $("#userMgmt-header").attr("href","/userManagement");
                    $("#cnxn-header").attr("href","/connectionManagement");
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
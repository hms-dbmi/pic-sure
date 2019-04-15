define(["header/header", "picSure/userFunctions", "picSure/applicationFunctions", "jquery","underscore"],
		function(header, userFunctions, applicationFunctions, $, _){
	// Register the pretty print for matcher debugs
	jasmine.pp = function(obj){return JSON.stringify(obj, undefined, 2);};
	describe("header", function(){
		describe("is a valid RequireJS module", function(){
			it("returns an object", function(){
				expect(typeof header).toEqual("object");
			});
			describe("has a logout function", function(){
				it("clears the sessionStorage and localStorage when invoked", function(){
					sessionStorage.setItem("foo", "bar");
					localStorage.setItem("bar", "foo");
					header.View.logout();
					expect(_.keys(localStorage)).toEqual([]);
					expect(_.keys(sessionStorage)).toEqual([]);
				});
			});

			// The following tests require changes to header.js which is not 
			// in scope for the current ticket. A new ticket has been generated 
			// for this fix().
			//
			// Basically there is not a good way to spy on the change in state
			// because the window.location = on line 21 refreshes the browser.
			//
			// Consider instead using a history.pushState call which can be spied on

//			describe("has a gotoLogin function", function(){
//				var logoutSpy;
//				it("invokes the logout function when invoked", function(){
//					logoutSpy = spyOn(header.View, "logout");
//					header.View.gotoLogin();
//					expect(header.View.logout).toHaveBeenCalled();
//				});
//
//				it("sends the user to the login page", function(){
//				logoutSpy = spyOn(history, "pushState");
//				header.View.gotoLogin();
//				expect(history.pushState).toHaveBeenCalled();
//				/* reset the url in the browser for developer sanity
//				* this way you can refresh the browser after one test run
//				*/
//				history.replaceState(undefined,"","/");
//				});
//			});
		
			describe("has a render function", function(){
				var userFunctionsSpy;
				var applicationFunctionsSpy;
				it("doesn't call userFunctions.me if on the tos page", function(){
					history.replaceState(undefined, "","/psamaui/tos");
					userFunctionsSpy = spyOn(userFunctions, "me");
					header.View.render();
					expect(userFunctions.me.calls.any()).toEqual(false);
					history.replaceState(undefined, "","/");
				});
				it("calls userFunctions.me if not on the tos page", function(){
					userFunctionsSpy = spyOn(userFunctions, "me");
					header.View.render();
					expect(userFunctions.me.calls.count()).toEqual(1);
				});
				describe("renders correctly", function(){
					it("shows the Super Admin Console button for SUPER_ADMIN users", function(){
						userFunctionsSpy = spyOn(userFunctions, "me").and
						.callFake(function(object, callback){
							callback({privileges: ['SUPER_ADMIN']});
						});
						applicationFunctionsSpy = spyOn(applicationFunctions, "fetchApplications").and
						.callFake(function(object, callback){
							callback([{}]);
						});
						header.View.render();
						expect($('a[href="/psamaui/roleManagement"]', header.View.$el).length).toEqual(1);
					});
					it("hides the Super Admin Console button for regular ADMIN users", function(){
						userFunctionsSpy = spyOn(userFunctions, "me").and
						.callFake(function(object, callback){
							callback({privileges: ['ADMIN']});
						});
						applicationFunctionsSpy = spyOn(applicationFunctions, "fetchApplications").and
						.callFake(function(object, callback){
							callback([{}]);
						});
						header.View.render();
						expect($('#super-admin-dropdown[style="visibility: hidden"]', header.View.$el).length).toEqual(1);
					});
					it("shows the Users button for ADMIN users", function(){
						userFunctionsSpy = spyOn(userFunctions, "me").and
						.callFake(function(object, callback){
							callback({privileges: ['ADMIN']});
						});
						applicationFunctionsSpy = spyOn(applicationFunctions, "fetchApplications").and
						.callFake(function(object, callback){
							callback([{}]);
						});
						header.View.render();
						expect($('a[href="/psamaui/userManagement"]', header.View.$el).length).toEqual(1);
					});
					it("hides the Users button for non-ADMIN users", function(){
						userFunctionsSpy = spyOn(userFunctions, "me").and
						.callFake(function(object, callback){
							callback({privileges: ['SUPER_ADMIN', 'SYSTEM','RESEARCHER','blahblah']});
						});
						applicationFunctionsSpy = spyOn(applicationFunctions, "fetchApplications").and
						.callFake(function(object, callback){
							callback([{}]);
						});
						header.View.render();
						expect($('a[href="/psamaui/userManagement"][style="visibility: hidden"]', header.View.$el).length).toEqual(1);
					});
					// Since it is possible that admin users would not have access to picsure, perhaps this is the wrong approach
					// That is not an issue related to the current ticket and those discussions will not be happening now.
					// it("shows the PIC-SURE UI button without needing any specific roles", function(){
					// 	userFunctionsSpy = spyOn(userFunctions, "me").and
					// 	.callFake(function(object, callback){
					// 		callback({privileges: []});
					// 	});
					// 	header.View.render();
					// 	expect($('a[href="/picsureui"]', header.View.$el).length).toEqual(1);
					// });
					it("shows application in Applications dropdown when at least one application has a link", function(){
						userFunctionsSpy = spyOn(userFunctions, "me").and
							.callFake(function(object, callback){
								callback({privileges: []});
							});
						applicationFunctionsSpy = spyOn(applicationFunctions, "fetchApplications").and
							.callFake(function(object, callback){
								callback([{uuid: 'app-uuid', name:'PICSURE-UI', url: '/picsureui'}]);
							});
						header.View.render();
						expect($('#applications-dropdown li a[href="/picsureui"]', header.View.$el).length).toEqual(1);
					});
					it("do not display any application in Applications dropdown when neither application has a link", function(){
						userFunctionsSpy = spyOn(userFunctions, "me").and
							.callFake(function(object, callback){
								callback({privileges: []});
							});
						applicationFunctionsSpy = spyOn(applicationFunctions, "fetchApplications").and
							.callFake(function(object, callback){
								callback([	{uuid: 'app-uuid-1', name:'PICSURE', url: ''},
											{uuid: 'app-uuid-2', name:'FRACTALIS', url: ''}]);
							});
						header.View.render();
						expect($('#applications-dropdown li', header.View.$el).length).toEqual(0);
					});
					describe("renders a Log Out button ", function(){
						it("Log Out button is rendered and has id logout-btn", function(){
							userFunctionsSpy = spyOn(userFunctions, "me").and
							.callFake(function(object, callback){
								callback({privileges: []});
							});
							applicationFunctionsSpy = spyOn(applicationFunctions, "fetchApplications").and
							.callFake(function(object, callback){
								callback([{}]);
							});
							header.View.render();
							expect($('a#logout-btn', header.View.$el).length).toEqual(1);
						});
						var logoutSpy;
						
						// See comment above regarding the window.location issue in header.js
						
//						it("when clicked the Log Out button triggers the header.View.logout function", function(){
//							logoutSpy = spyOn(header.View, "logout");
//							userFunctionsSpy = spyOn(userFunctions, "me").and
//							.callFake(function(object, callback){
//								callback({privileges: []});
//							});
//							header.View.render();
//							$('a#logout-btn', header.View.$el).click();
//							expect(header.View.logout).toHaveBeenCalled();
//							history.replaceState(undefined,"","/");
//						});
					});
				});
			});
		});

	});
});
